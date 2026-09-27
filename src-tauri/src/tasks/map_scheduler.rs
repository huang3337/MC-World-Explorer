use super::{MapFailureKind, MapResultIdentity, MapResultItem, MapResultPayload, MapResultQueue};
use mcwe_core::cancel::Cancellation;
use std::{
    collections::{HashMap, HashSet},
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Condvar, Mutex, MutexGuard,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

pub const MAP_WORKER_COUNT: usize = 2;
pub const MAP_PENDING_CAPACITY: usize = 96;

type Work = Box<dyn FnOnce(MapTaskHandle) + Send + 'static>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapSchedulerError {
    WorkerUnavailable,
    ShuttingDown,
    ViewportOutdated,
    IdExhausted,
    QueueFull,
    InvalidKey,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapSubmitOutcome {
    Queued,
    Rebound,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MapJobKey {
    session_id: u64,
    tile_x: i32,
    tile_z: i32,
    blocks_per_pixel: u8,
}

impl MapJobKey {
    pub fn new(
        session_id: u64,
        tile_x: i32,
        tile_z: i32,
        blocks_per_pixel: u8,
    ) -> Result<Self, MapSchedulerError> {
        if session_id == 0
            || session_id > crate::protocol::MAX_SAFE_INTEGER_ID
            || !matches!(blocks_per_pixel, 1 | 2 | 4 | 8 | 16)
        {
            return Err(MapSchedulerError::InvalidKey);
        }
        Ok(Self {
            session_id,
            tile_x,
            tile_z,
            blocks_per_pixel,
        })
    }

    pub fn session_id(self) -> u64 {
        self.session_id
    }

    pub fn tile_x(self) -> i32 {
        self.tile_x
    }

    pub fn tile_z(self) -> i32 {
        self.tile_z
    }

    pub fn blocks_per_pixel(self) -> u8 {
        self.blocks_per_pixel
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct MapPriority {
    class: u8,
    distance_squared: u64,
    tile_z: i32,
    tile_x: i32,
}

impl MapPriority {
    pub fn visible(distance_squared: u64, tile_x: i32, tile_z: i32) -> Self {
        Self {
            class: 0,
            distance_squared,
            tile_z,
            tile_x,
        }
    }

    pub fn prefetch(distance_squared: u64, tile_x: i32, tile_z: i32) -> Self {
        Self {
            class: 1,
            distance_squared,
            tile_z,
            tile_x,
        }
    }

    fn publishes_results(self) -> bool {
        self.class == 0
    }
}

#[derive(Debug)]
struct MapTaskControl {
    session_id: u64,
    viewport_id: AtomicU64,
    cancelled: AtomicBool,
    completed: AtomicBool,
    publish_results: AtomicBool,
}

#[derive(Debug, Clone)]
pub struct MapTaskHandle {
    control: Arc<MapTaskControl>,
}

impl MapTaskHandle {
    pub fn session_id(&self) -> u64 {
        self.control.session_id
    }

    pub fn viewport_id(&self) -> u64 {
        self.control.viewport_id.load(Ordering::SeqCst)
    }

    pub fn cancel(&self) {
        self.control.cancelled.store(true, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.control.cancelled.load(Ordering::SeqCst)
    }

    pub fn mark_completed(&self) {
        self.control.completed.store(true, Ordering::SeqCst);
    }

    pub fn should_publish_results(&self) -> bool {
        self.control.publish_results.load(Ordering::SeqCst)
    }

    fn rebind(&self, viewport_id: u64) {
        self.control
            .viewport_id
            .store(viewport_id, Ordering::SeqCst);
    }

    fn promote_to_visible(&self) {
        self.control.publish_results.store(true, Ordering::SeqCst);
    }

    fn same_task(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.control, &other.control)
    }
}

impl Cancellation for MapTaskHandle {
    fn is_cancelled(&self) -> bool {
        self.is_cancelled()
    }
}

struct Job {
    key: MapJobKey,
    priority: MapPriority,
    sequence: u64,
    handle: MapTaskHandle,
    work: Work,
}

#[derive(Debug, Clone, Copy)]
struct CurrentViewport {
    session_id: u64,
    id: u64,
}

struct SchedulerState {
    current: Option<CurrentViewport>,
    last_viewport_id: u64,
    next_sequence: u64,
    pending: Vec<Job>,
    active: HashMap<MapJobKey, MapTaskHandle>,
    shutting_down: bool,
    worker_count: usize,
    workers_exited: usize,
}

impl SchedulerState {
    fn new(worker_count: usize) -> Self {
        Self {
            current: None,
            last_viewport_id: 0,
            next_sequence: 0,
            pending: Vec::new(),
            active: HashMap::new(),
            shutting_down: false,
            worker_count,
            workers_exited: 0,
        }
    }
}

struct Shared {
    state: Mutex<SchedulerState>,
    changed: Condvar,
    results: Arc<MapResultQueue>,
}

struct WorkerExit(Arc<Shared>);

impl Drop for WorkerExit {
    fn drop(&mut self) {
        let mut state = lock(&self.0.state);
        state.workers_exited += 1;
        self.0.changed.notify_all();
    }
}

pub struct MapScheduler {
    shared: Arc<Shared>,
    workers: Mutex<Vec<JoinHandle<()>>>,
}

impl Default for MapScheduler {
    fn default() -> Self {
        Self::new().unwrap_or_else(|_| Self::unavailable(Arc::new(MapResultQueue::default())))
    }
}

impl MapScheduler {
    pub fn new() -> std::io::Result<Self> {
        Self::with_results(Arc::new(MapResultQueue::default()))
    }

    pub(crate) fn with_results(results: Arc<MapResultQueue>) -> std::io::Result<Self> {
        let shared = Arc::new(Shared {
            state: Mutex::new(SchedulerState::new(MAP_WORKER_COUNT)),
            changed: Condvar::new(),
            results,
        });
        let mut workers = Vec::with_capacity(MAP_WORKER_COUNT);
        for index in 0..MAP_WORKER_COUNT {
            let worker_shared = Arc::clone(&shared);
            match thread::Builder::new()
                .name(format!("mcwe-map-worker-{}", index + 1))
                .spawn(move || worker_loop(worker_shared))
            {
                Ok(worker) => workers.push(worker),
                Err(error) => {
                    {
                        let mut state = lock(&shared.state);
                        state.shutting_down = true;
                        state.worker_count = workers.len();
                    }
                    shared.changed.notify_all();
                    for worker in workers {
                        let _ = worker.join();
                    }
                    return Err(error);
                }
            }
        }
        Ok(Self {
            shared,
            workers: Mutex::new(workers),
        })
    }

    pub(crate) fn unavailable(results: Arc<MapResultQueue>) -> Self {
        Self {
            shared: Arc::new(Shared {
                state: Mutex::new(SchedulerState::new(0)),
                changed: Condvar::new(),
                results,
            }),
            workers: Mutex::new(Vec::new()),
        }
    }

    pub fn begin_viewport(
        &self,
        session_id: u64,
        retained: &HashSet<MapJobKey>,
    ) -> Result<u64, MapSchedulerError> {
        self.begin_viewport_with_background(session_id, retained, &HashSet::new())
    }

    pub fn begin_viewport_with_background(
        &self,
        session_id: u64,
        visible: &HashSet<MapJobKey>,
        background: &HashSet<MapJobKey>,
    ) -> Result<u64, MapSchedulerError> {
        if session_id == 0
            || session_id > crate::protocol::MAX_SAFE_INTEGER_ID
            || visible.iter().any(|key| key.session_id != session_id)
            || background.iter().any(|key| key.session_id != session_id)
        {
            return Err(MapSchedulerError::InvalidKey);
        }
        let mut state = lock(&self.shared.state);
        if state.shutting_down {
            return Err(MapSchedulerError::ShuttingDown);
        }
        ensure_available(&state)?;
        if state.last_viewport_id >= crate::protocol::MAX_SAFE_INTEGER_ID {
            return Err(MapSchedulerError::IdExhausted);
        }
        let viewport_id = state.last_viewport_id + 1;
        self.shared
            .results
            .activate_viewport(session_id, viewport_id)
            .map_err(|_| MapSchedulerError::ShuttingDown)?;
        state.last_viewport_id = viewport_id;
        state.current = Some(CurrentViewport {
            session_id,
            id: viewport_id,
        });

        state.pending.retain(|job| {
            let keep_visible = visible.contains(&job.key);
            let keep_background = background.contains(&job.key) && job.priority.class == 1;
            if keep_visible || keep_background {
                job.handle.rebind(viewport_id);
                if keep_visible {
                    job.handle.promote_to_visible();
                }
            } else {
                job.handle.cancel();
            }
            keep_visible || keep_background
        });
        for (key, handle) in &state.active {
            let keep_visible = visible.contains(key);
            let keep_background = background.contains(key) && !handle.should_publish_results();
            if (keep_visible || keep_background)
                && !handle.is_cancelled()
                && !handle.control.completed.load(Ordering::SeqCst)
            {
                handle.rebind(viewport_id);
                if keep_visible {
                    handle.promote_to_visible();
                }
            } else {
                handle.cancel();
            }
        }
        drop(state);
        self.shared.changed.notify_all();
        Ok(viewport_id)
    }

    pub fn submit<F>(
        &self,
        viewport_id: u64,
        key: MapJobKey,
        priority: MapPriority,
        work: F,
    ) -> Result<MapSubmitOutcome, MapSchedulerError>
    where
        F: FnOnce(MapTaskHandle) + Send + 'static,
    {
        let mut state = lock(&self.shared.state);
        if state.shutting_down {
            return Err(MapSchedulerError::ShuttingDown);
        }
        ensure_available(&state)?;
        let current = state
            .current
            .filter(|current| current.id == viewport_id && current.session_id == key.session_id)
            .ok_or(MapSchedulerError::ViewportOutdated)?;

        if let Some(existing) = state.active.get(&key).filter(|handle| {
            !handle.is_cancelled() && !handle.control.completed.load(Ordering::SeqCst)
        }) {
            existing.rebind(current.id);
            if priority.publishes_results() {
                existing.promote_to_visible();
            }
            return Ok(MapSubmitOutcome::Rebound);
        }
        if let Some(existing) = state.pending.iter_mut().find(|job| job.key == key) {
            existing.priority = priority;
            existing.handle.rebind(current.id);
            if priority.publishes_results() {
                existing.handle.promote_to_visible();
            }
            self.shared.changed.notify_all();
            return Ok(MapSubmitOutcome::Rebound);
        }
        if state.pending.len() >= MAP_PENDING_CAPACITY {
            return Err(MapSchedulerError::QueueFull);
        }
        let sequence = state.next_sequence;
        state.next_sequence = state
            .next_sequence
            .checked_add(1)
            .ok_or(MapSchedulerError::IdExhausted)?;
        let handle = MapTaskHandle {
            control: Arc::new(MapTaskControl {
                session_id: key.session_id,
                viewport_id: AtomicU64::new(current.id),
                cancelled: AtomicBool::new(false),
                completed: AtomicBool::new(false),
                publish_results: AtomicBool::new(priority.publishes_results()),
            }),
        };
        state.pending.push(Job {
            key,
            priority,
            sequence,
            handle,
            work: Box::new(work),
        });
        drop(state);
        self.shared.changed.notify_one();
        Ok(MapSubmitOutcome::Queued)
    }

    pub fn shutdown(&self, timeout: Duration) -> bool {
        self.begin_shutdown();
        self.await_shutdown(timeout)
    }

    pub fn abort_viewport(&self, session_id: u64, viewport_id: u64) {
        let mut state = lock(&self.shared.state);
        if !state
            .current
            .is_some_and(|current| current.session_id == session_id && current.id == viewport_id)
        {
            return;
        }
        state.current = None;
        state.pending.retain(|job| {
            if job.handle.viewport_id() == viewport_id {
                job.handle.cancel();
                false
            } else {
                true
            }
        });
        for handle in state.active.values() {
            if handle.viewport_id() == viewport_id {
                handle.cancel();
            }
        }
        drop(state);
        self.shared
            .results
            .discard_viewport(session_id, viewport_id);
        self.shared.changed.notify_all();
    }

    pub(crate) fn current_viewport(&self) -> Option<(u64, u64)> {
        lock(&self.shared.state)
            .current
            .map(|current| (current.session_id, current.id))
    }

    pub(crate) fn await_shutdown(&self, timeout: Duration) -> bool {
        let deadline = Instant::now().checked_add(timeout);
        let mut state = lock(&self.shared.state);
        while state.workers_exited < state.worker_count {
            let Some(remaining) =
                deadline.and_then(|end| end.checked_duration_since(Instant::now()))
            else {
                return false;
            };
            let waited = self.shared.changed.wait_timeout(state, remaining);
            let (next, timeout_result) = waited.unwrap_or_else(|poisoned| poisoned.into_inner());
            state = next;
            if timeout_result.timed_out() && state.workers_exited < state.worker_count {
                return false;
            }
        }
        drop(state);
        self.join_workers()
    }

    pub(crate) fn begin_shutdown(&self) {
        let mut state = lock(&self.shared.state);
        state.shutting_down = true;
        state.current = None;
        for job in state.pending.drain(..) {
            job.handle.cancel();
        }
        for handle in state.active.values() {
            handle.cancel();
        }
        drop(state);
        self.shared.changed.notify_all();
    }

    fn join_workers(&self) -> bool {
        let workers = std::mem::take(&mut *lock(&self.workers));
        let mut joined = true;
        for worker in workers {
            joined &= worker.join().is_ok();
        }
        joined
    }
}

impl Drop for MapScheduler {
    fn drop(&mut self) {
        self.begin_shutdown();
        let _ = self.join_workers();
    }
}

fn ensure_available(state: &SchedulerState) -> Result<(), MapSchedulerError> {
    if state.worker_count == 0 || state.workers_exited == state.worker_count {
        Err(MapSchedulerError::WorkerUnavailable)
    } else {
        Ok(())
    }
}

fn worker_loop(shared: Arc<Shared>) {
    let _exit = WorkerExit(Arc::clone(&shared));
    loop {
        let job = {
            let mut state = lock(&shared.state);
            while state.pending.is_empty() && !state.shutting_down {
                state = shared
                    .changed
                    .wait(state)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            if state.shutting_down {
                return;
            }
            let Some(index) = state
                .pending
                .iter()
                .enumerate()
                .min_by_key(|(_, job)| (job.priority, job.sequence))
                .map(|(index, _)| index)
            else {
                continue;
            };
            let job = state.pending.swap_remove(index);
            state.active.insert(job.key, job.handle.clone());
            job
        };

        let panicked = catch_unwind(AssertUnwindSafe(|| (job.work)(job.handle.clone()))).is_err();
        if panicked && job.handle.should_publish_results() {
            if let Ok(identity) =
                MapResultIdentity::new(job.handle.session_id(), job.handle.viewport_id(), job.key)
            {
                let _ = shared.results.push_terminal(
                    MapResultItem {
                        identity,
                        payload: MapResultPayload::Failed(MapFailureKind::WorkerPanicked),
                    },
                    &job.handle,
                );
            }
        }
        let mut state = lock(&shared.state);
        if state
            .active
            .get(&job.key)
            .is_some_and(|active| active.same_task(&job.handle))
        {
            state.active.remove(&job.key);
        }
        shared.changed.notify_all();
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        mpsc,
    };

    fn key(x: i32) -> MapJobKey {
        MapJobKey::new(1, x, 0, 1).unwrap()
    }

    fn wait_for(receiver: &mpsc::Receiver<i32>) -> i32 {
        receiver.recv_timeout(Duration::from_secs(2)).unwrap()
    }

    #[test]
    fn runs_exactly_two_workers_and_keeps_extra_work_queued() {
        let scheduler = MapScheduler::default();
        let viewport = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = mpsc::channel();
        let (done_tx, done_rx) = mpsc::channel();
        for x in 0..3 {
            let release = Arc::clone(&release);
            let active = Arc::clone(&active);
            let peak = Arc::clone(&peak);
            let started = started_tx.clone();
            let done = done_tx.clone();
            scheduler
                .submit(
                    viewport,
                    key(x),
                    MapPriority::visible(x as u64, x, 0),
                    move |_| {
                        let current = active.fetch_add(1, Ordering::SeqCst) + 1;
                        peak.fetch_max(current, Ordering::SeqCst);
                        started.send(x).unwrap();
                        let (gate, changed) = &*release;
                        let guard = lock(gate);
                        drop(
                            changed
                                .wait_while(guard, |released| !*released)
                                .unwrap_or_else(|poisoned| poisoned.into_inner()),
                        );
                        active.fetch_sub(1, Ordering::SeqCst);
                        done.send(x).unwrap();
                    },
                )
                .unwrap();
        }
        let _ = wait_for(&started_rx);
        let _ = wait_for(&started_rx);
        assert!(started_rx.recv_timeout(Duration::from_millis(40)).is_err());
        assert_eq!(peak.load(Ordering::SeqCst), MAP_WORKER_COUNT);
        {
            let (gate, changed) = &*release;
            *lock(gate) = true;
            changed.notify_all();
        }
        for _ in 0..3 {
            let _ = wait_for(&done_rx);
        }
        assert_eq!(peak.load(Ordering::SeqCst), MAP_WORKER_COUNT);
    }

    #[test]
    fn visible_work_precedes_prefetch_and_equal_priorities_are_stable() {
        let scheduler = MapScheduler::default();
        let viewport = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (block_tx, block_rx) = mpsc::channel();
        for x in [90, 91] {
            let release = Arc::clone(&release);
            let started = block_tx.clone();
            scheduler
                .submit(viewport, key(x), MapPriority::visible(0, x, 0), move |_| {
                    started.send(x).unwrap();
                    let (gate, changed) = &*release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                })
                .unwrap();
        }
        let _ = wait_for(&block_rx);
        let _ = wait_for(&block_rx);

        let (order_tx, order_rx) = mpsc::channel();
        for (x, priority) in [
            (1, MapPriority::prefetch(0, 1, 0)),
            (2, MapPriority::visible(4, 2, 0)),
            (3, MapPriority::visible(1, 3, 0)),
        ] {
            let order = order_tx.clone();
            scheduler
                .submit(viewport, key(x), priority, move |_| order.send(x).unwrap())
                .unwrap();
        }
        {
            let (gate, changed) = &*release;
            *lock(gate) = true;
            changed.notify_all();
        }
        let first = wait_for(&order_rx);
        let second = wait_for(&order_rx);
        let third = wait_for(&order_rx);
        assert_eq!(HashSet::from([first, second]), HashSet::from([2, 3]));
        assert_eq!(third, 1);
    }

    #[test]
    fn queued_anchor_prefetch_survives_viewport_change_without_becoming_visible() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (block_tx, block_rx) = mpsc::channel();
        for x in [90, 91] {
            let release = Arc::clone(&release);
            let started = block_tx.clone();
            scheduler
                .submit(first, key(x), MapPriority::visible(0, x, 0), move |_| {
                    started.send(x).unwrap();
                    let (gate, changed) = &*release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                })
                .unwrap();
        }
        let _ = wait_for(&block_rx);
        let _ = wait_for(&block_rx);

        let (order_tx, order_rx) = mpsc::channel();
        let background_order = order_tx.clone();
        scheduler
            .submit(
                first,
                key(1),
                MapPriority::prefetch(0, 1, 0),
                move |handle| {
                    background_order
                        .send((1, handle.should_publish_results(), handle.viewport_id()))
                        .unwrap();
                },
            )
            .unwrap();
        let second = scheduler
            .begin_viewport_with_background(
                1,
                &HashSet::from([key(2), key(3)]),
                &HashSet::from([key(1)]),
            )
            .unwrap();
        let visible_release = Arc::new((Mutex::new(false), Condvar::new()));
        for x in [2, 3] {
            let order = order_tx.clone();
            let visible_release = Arc::clone(&visible_release);
            scheduler
                .submit(
                    second,
                    key(x),
                    MapPriority::visible(0, x, 0),
                    move |handle| {
                        order
                            .send((x, handle.should_publish_results(), handle.viewport_id()))
                            .unwrap();
                        let (gate, changed) = &*visible_release;
                        let guard = lock(gate);
                        drop(
                            changed
                                .wait_while(guard, |released| !*released)
                                .unwrap_or_else(|poisoned| poisoned.into_inner()),
                        );
                    },
                )
                .unwrap();
        }
        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();
        let first_two = [
            order_rx.recv_timeout(Duration::from_secs(2)),
            order_rx.recv_timeout(Duration::from_secs(2)),
        ];
        let (gate, changed) = &*visible_release;
        *lock(gate) = true;
        changed.notify_all();
        let first_two = [
            first_two[0].as_ref().unwrap(),
            first_two[1].as_ref().unwrap(),
        ];
        assert_eq!(
            HashSet::from([first_two[0].0, first_two[1].0]),
            HashSet::from([2, 3])
        );
        assert!(first_two.iter().all(|item| item.1 && item.2 == second));
        assert_eq!(
            order_rx.recv_timeout(Duration::from_secs(2)).unwrap(),
            (1, false, second)
        );
    }

    #[test]
    fn equal_priorities_keep_submission_order_when_one_worker_is_available() {
        let scheduler = MapScheduler::default();
        let viewport = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let releases = [
            Arc::new((Mutex::new(false), Condvar::new())),
            Arc::new((Mutex::new(false), Condvar::new())),
        ];
        let (block_tx, block_rx) = mpsc::channel();
        for (index, tile_x) in [90, 91].into_iter().enumerate() {
            let release = Arc::clone(&releases[index]);
            let started = block_tx.clone();
            scheduler
                .submit(
                    viewport,
                    key(tile_x),
                    MapPriority::visible(0, tile_x, 0),
                    move |_| {
                        started.send(tile_x).unwrap();
                        let (gate, changed) = &*release;
                        let guard = lock(gate);
                        drop(
                            changed
                                .wait_while(guard, |released| !*released)
                                .unwrap_or_else(|poisoned| poisoned.into_inner()),
                        );
                    },
                )
                .unwrap();
        }
        let _ = wait_for(&block_rx);
        let _ = wait_for(&block_rx);

        let (order_tx, order_rx) = mpsc::channel();
        let equal_priority = MapPriority::visible(0, 0, 0);
        for tile_x in [1, 2, 3] {
            let order = order_tx.clone();
            scheduler
                .submit(viewport, key(tile_x), equal_priority, move |_| {
                    order.send(tile_x).unwrap()
                })
                .unwrap();
        }
        let (gate, changed) = &*releases[0];
        *lock(gate) = true;
        changed.notify_all();
        assert_eq!(wait_for(&order_rx), 1);
        assert_eq!(wait_for(&order_rx), 2);
        assert_eq!(wait_for(&order_rx), 3);
        let (gate, changed) = &*releases[1];
        *lock(gate) = true;
        changed.notify_all();
    }

    #[test]
    fn retained_pending_job_rebinds_without_duplicate_execution() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        for x in [80, 81] {
            let release = Arc::clone(&release);
            let started = started_tx.clone();
            scheduler
                .submit(first, key(x), MapPriority::visible(0, x, 0), move |_| {
                    started.send(x).unwrap();
                    let (gate, changed) = &*release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                })
                .unwrap();
        }
        let _ = wait_for(&started_rx);
        let _ = wait_for(&started_rx);

        let target = key(5);
        let executions = Arc::new(AtomicUsize::new(0));
        let (owner_tx, owner_rx) = mpsc::channel();
        let count = Arc::clone(&executions);
        scheduler
            .submit(
                first,
                target,
                MapPriority::visible(10, 5, 0),
                move |handle| {
                    count.fetch_add(1, Ordering::SeqCst);
                    owner_tx.send(handle.viewport_id() as i32).unwrap();
                },
            )
            .unwrap();
        let second = scheduler
            .begin_viewport(1, &HashSet::from([target]))
            .unwrap();
        assert_eq!(
            scheduler
                .submit(second, target, MapPriority::visible(0, 5, 0), |_| {})
                .unwrap(),
            MapSubmitOutcome::Rebound
        );
        {
            let (gate, changed) = &*release;
            *lock(gate) = true;
            changed.notify_all();
        }
        assert_eq!(wait_for(&owner_rx), second as i32);
        assert_eq!(executions.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn active_prefetch_is_promoted_when_it_becomes_visible() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let target = key(5);
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let worker_release = Arc::clone(&release);
        scheduler
            .submit(
                first,
                target,
                MapPriority::prefetch(0, 5, 0),
                move |handle| {
                    started_tx.send(handle.should_publish_results()).unwrap();
                    let (gate, changed) = &*worker_release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                    finished_tx
                        .send((handle.should_publish_results(), handle.viewport_id()))
                        .unwrap();
                },
            )
            .unwrap();
        assert!(!started_rx.recv_timeout(Duration::from_secs(1)).unwrap());

        let second = scheduler
            .begin_viewport(1, &HashSet::from([target]))
            .unwrap();
        assert_eq!(
            scheduler
                .submit(second, target, MapPriority::visible(0, 5, 0), |_| {})
                .unwrap(),
            MapSubmitOutcome::Rebound
        );
        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();
        assert_eq!(
            finished_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            (true, second)
        );
    }

    #[test]
    fn active_anchor_prefetch_survives_viewport_change_as_background() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let target = key(5);
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let worker_release = Arc::clone(&release);
        scheduler
            .submit(
                first,
                target,
                MapPriority::prefetch(0, 5, 0),
                move |handle| {
                    started_tx.send(()).unwrap();
                    let (gate, changed) = &*worker_release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                    finished_tx
                        .send((
                            handle.is_cancelled(),
                            handle.viewport_id(),
                            handle.should_publish_results(),
                        ))
                        .unwrap();
                },
            )
            .unwrap();
        started_rx.recv_timeout(Duration::from_secs(1)).unwrap();

        let second = scheduler
            .begin_viewport_with_background(1, &HashSet::new(), &HashSet::from([target]))
            .unwrap();
        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();

        assert_eq!(
            finished_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            (false, second, false)
        );
    }

    #[test]
    fn active_prefetch_outside_new_background_is_cancelled() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let target = key(6);
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let worker_release = Arc::clone(&release);
        scheduler
            .submit(
                first,
                target,
                MapPriority::prefetch(0, 6, 0),
                move |handle| {
                    started_tx.send(()).unwrap();
                    let (gate, changed) = &*worker_release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                    finished_tx
                        .send((handle.is_cancelled(), handle.viewport_id()))
                        .unwrap();
                },
            )
            .unwrap();
        started_rx.recv_timeout(Duration::from_secs(1)).unwrap();

        scheduler
            .begin_viewport_with_background(1, &HashSet::new(), &HashSet::new())
            .unwrap();
        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();

        assert_eq!(
            finished_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            (true, first)
        );
    }

    #[test]
    fn queue_is_bounded_and_new_viewport_cancels_active_work() {
        let scheduler = MapScheduler::default();
        let viewport = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let (started_tx, started_rx) = mpsc::channel();
        let (cancelled_tx, cancelled_rx) = mpsc::channel();
        for x in [100, 101] {
            let started = started_tx.clone();
            let cancelled = cancelled_tx.clone();
            scheduler
                .submit(
                    viewport,
                    key(x),
                    MapPriority::visible(0, x, 0),
                    move |handle| {
                        started.send(x).unwrap();
                        while !handle.is_cancelled() {
                            thread::yield_now();
                        }
                        cancelled.send(x).unwrap();
                    },
                )
                .unwrap();
        }
        let _ = wait_for(&started_rx);
        let _ = wait_for(&started_rx);
        for x in 0..MAP_PENDING_CAPACITY as i32 {
            scheduler
                .submit(
                    viewport,
                    key(x),
                    MapPriority::visible(x as u64, x, 0),
                    |_| {},
                )
                .unwrap();
        }
        assert_eq!(
            scheduler.submit(
                viewport,
                key(500),
                MapPriority::visible(500, 500, 0),
                |_| {}
            ),
            Err(MapSchedulerError::QueueFull)
        );
        let next = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        assert!(next > viewport);
        let _ = wait_for(&cancelled_rx);
        let _ = wait_for(&cancelled_rx);
    }

    #[test]
    fn returning_to_a_cancelled_active_tile_queues_fresh_work() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let target = key(5);
        for tile_key in [target, key(6)] {
            let release = Arc::clone(&release);
            let started = started_tx.clone();
            scheduler
                .submit(
                    first,
                    tile_key,
                    MapPriority::visible(0, tile_key.tile_x(), 0),
                    move |handle| {
                        started.send(handle.is_cancelled()).unwrap();
                        let (gate, changed) = &*release;
                        let guard = lock(gate);
                        drop(
                            changed
                                .wait_while(guard, |released| !*released)
                                .unwrap_or_else(|poisoned| poisoned.into_inner()),
                        );
                    },
                )
                .unwrap();
        }
        assert!(!started_rx.recv_timeout(Duration::from_secs(2)).unwrap());
        assert!(!started_rx.recv_timeout(Duration::from_secs(2)).unwrap());

        let _away = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let returned = scheduler
            .begin_viewport(1, &HashSet::from([target]))
            .unwrap();
        let (fresh_tx, fresh_rx) = mpsc::channel();
        assert_eq!(
            scheduler
                .submit(
                    returned,
                    target,
                    MapPriority::visible(0, target.tile_x(), 0),
                    move |handle| fresh_tx.send(handle.is_cancelled()).unwrap(),
                )
                .unwrap(),
            MapSubmitOutcome::Queued
        );
        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();
        assert!(!fresh_rx.recv_timeout(Duration::from_secs(2)).unwrap());
    }

    #[test]
    fn completed_but_not_yet_removed_work_is_not_rebound() {
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let target = key(5);
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (completed_tx, completed_rx) = mpsc::channel();
        let old_release = Arc::clone(&release);
        scheduler
            .submit(
                first,
                target,
                MapPriority::visible(0, 5, 0),
                move |handle| {
                    handle.mark_completed();
                    completed_tx.send(()).unwrap();
                    let (gate, changed) = &*old_release;
                    let guard = lock(gate);
                    drop(
                        changed
                            .wait_while(guard, |released| !*released)
                            .unwrap_or_else(|poisoned| poisoned.into_inner()),
                    );
                },
            )
            .unwrap();
        completed_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        let next = scheduler
            .begin_viewport(1, &HashSet::from([target]))
            .unwrap();
        let (fresh_tx, fresh_rx) = mpsc::channel();
        assert_eq!(
            scheduler
                .submit(next, target, MapPriority::visible(0, 5, 0), move |_| {
                    fresh_tx.send(()).unwrap()
                })
                .unwrap(),
            MapSubmitOutcome::Queued
        );
        fresh_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();
    }

    #[test]
    fn worker_panic_is_isolated_and_shutdown_is_bounded() {
        let results = Arc::new(MapResultQueue::default());
        let scheduler = MapScheduler::with_results(Arc::clone(&results)).unwrap();
        let viewport = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let (done_tx, done_rx) = mpsc::channel();
        scheduler
            .submit(viewport, key(1), MapPriority::visible(0, 1, 0), |_| {
                panic!("synthetic map worker panic")
            })
            .unwrap();
        scheduler
            .submit(viewport, key(2), MapPriority::visible(1, 2, 0), move |_| {
                done_tx.send(2).unwrap()
            })
            .unwrap();
        assert_eq!(wait_for(&done_rx), 2);
        let failures = results
            .wait_and_drain(
                1,
                usize::MAX,
                Duration::from_secs(1),
                &AtomicBool::new(false),
            )
            .items;
        assert_eq!(failures.len(), 1);
        assert!(matches!(
            failures[0].payload,
            MapResultPayload::Failed(MapFailureKind::WorkerPanicked)
        ));
        assert!(scheduler.shutdown(Duration::from_secs(1)));
        assert_eq!(
            scheduler.submit(viewport, key(3), MapPriority::visible(2, 3, 0), |_| {}),
            Err(MapSchedulerError::ShuttingDown)
        );
    }

    #[test]
    fn prefetch_panic_does_not_publish_an_unconsumed_failure() {
        let results = Arc::new(MapResultQueue::default());
        let scheduler = MapScheduler::with_results(Arc::clone(&results)).unwrap();
        let viewport = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let (started_tx, started_rx) = mpsc::channel();
        scheduler
            .submit(
                viewport,
                key(1),
                MapPriority::prefetch(0, 1, 0),
                move |_| {
                    started_tx.send(()).unwrap();
                    panic!("synthetic prefetch panic")
                },
            )
            .unwrap();
        started_rx.recv_timeout(Duration::from_secs(1)).unwrap();
        let drained = results.wait_and_drain(
            1,
            usize::MAX,
            Duration::from_millis(100),
            &AtomicBool::new(false),
        );
        assert_eq!(drained.outcome, crate::tasks::MapDrainOutcome::Timeout);
        assert!(drained.items.is_empty());
    }

    #[test]
    fn rejects_invalid_keys_and_outdated_viewports() {
        assert_eq!(
            MapJobKey::new(0, 0, 0, 1),
            Err(MapSchedulerError::InvalidKey)
        );
        assert_eq!(
            MapJobKey::new(1, 0, 0, 3),
            Err(MapSchedulerError::InvalidKey)
        );
        let scheduler = MapScheduler::default();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let second = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        assert_eq!(
            scheduler.submit(first, key(1), MapPriority::visible(0, 1, 0), |_| {}),
            Err(MapSchedulerError::ViewportOutdated)
        );
        assert_eq!(
            scheduler.submit(second, key(1), MapPriority::visible(0, 1, 0), |_| {}),
            Ok(MapSubmitOutcome::Queued)
        );
    }

    #[test]
    fn beginning_a_new_viewport_discards_queued_results_from_the_old_generation() {
        let results = Arc::new(MapResultQueue::default());
        let scheduler = MapScheduler::with_results(Arc::clone(&results)).unwrap();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let tile_key = key(1);
        results
            .push_partial(MapResultItem {
                identity: MapResultIdentity::new(1, first, tile_key).unwrap(),
                payload: MapResultPayload::Partial(mcwe_core::map::MapTilePatch {
                    request: mcwe_core::map::MapTileRequest {
                        tile_x: 1,
                        tile_z: 0,
                        blocks_per_pixel: 1,
                    },
                    revision: 1,
                    pixel_x: 0,
                    pixel_z: 0,
                    width: 1,
                    height: 1,
                    colors: vec![0],
                    heights: vec![0],
                    statuses: vec![4],
                    completed_chunks: 1,
                    total_chunks: 256,
                }),
            })
            .unwrap();
        scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        assert!(results.drain(1, usize::MAX).is_empty());
    }

    #[test]
    fn late_results_cannot_enter_after_viewport_switch_or_abort() {
        use crate::tasks::MapResultQueueError;
        use mcwe_core::cancel::NeverCancel;

        let results = Arc::new(MapResultQueue::default());
        let scheduler = MapScheduler::with_results(Arc::clone(&results)).unwrap();
        let first = scheduler.begin_viewport(1, &HashSet::new()).unwrap();
        let tile_key = key(1);
        let old_identity = MapResultIdentity::new(1, first, tile_key).unwrap();
        let second = scheduler.begin_viewport(1, &HashSet::new()).unwrap();

        assert_eq!(
            results.push_partial(MapResultItem {
                identity: old_identity,
                payload: MapResultPayload::Partial(mcwe_core::map::MapTilePatch {
                    request: mcwe_core::map::MapTileRequest {
                        tile_x: 1,
                        tile_z: 0,
                        blocks_per_pixel: 1,
                    },
                    revision: 1,
                    pixel_x: 0,
                    pixel_z: 0,
                    width: 1,
                    height: 1,
                    colors: vec![0],
                    heights: vec![0],
                    statuses: vec![4],
                    completed_chunks: 1,
                    total_chunks: 256,
                }),
            }),
            Ok(crate::tasks::PartialPushOutcome::DroppedForOutdatedViewport)
        );
        assert_eq!(
            results.push_terminal(
                MapResultItem {
                    identity: old_identity,
                    payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
                },
                &NeverCancel,
            ),
            Err(MapResultQueueError::OutdatedRevision)
        );

        let current_identity = MapResultIdentity::new(1, second, tile_key).unwrap();
        results
            .push_terminal(
                MapResultItem {
                    identity: current_identity,
                    payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
                },
                &NeverCancel,
            )
            .unwrap();
        assert_eq!(results.drain(2, usize::MAX).len(), 1);

        scheduler.abort_viewport(1, second);
        assert_eq!(
            results.push_terminal(
                MapResultItem {
                    identity: current_identity,
                    payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
                },
                &NeverCancel,
            ),
            Err(MapResultQueueError::OutdatedRevision)
        );
    }
}
