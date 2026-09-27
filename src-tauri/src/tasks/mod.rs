mod executor;
mod map_poll;
mod map_results;
mod map_scheduler;
mod registry;

pub use executor::{ExecutorError, JobReceipt, TaskExecutor};
pub use map_poll::{MapPollBatch, MapPollCoordinator, MapPollError, MapPollRequest, MapPollState};
pub use map_results::{
    MapDrainBatch, MapDrainOutcome, MapFailureKind, MapResultIdentity, MapResultItem,
    MapResultPayload, MapResultQueue, MapResultQueueError, PartialPushOutcome,
    MAP_RESULT_MAX_BYTES, MAP_RESULT_MAX_ITEMS, MAP_RESULT_MAX_ITEM_BYTES,
};
pub use map_scheduler::{
    MapJobKey, MapPriority, MapScheduler, MapSchedulerError, MapSubmitOutcome, MapTaskHandle,
    MAP_PENDING_CAPACITY, MAP_WORKER_COUNT,
};
pub use registry::{TaskError, TaskHandle, TaskRegistry};

use mcwe_core::CoreError;
use std::{
    collections::HashSet,
    sync::Arc,
    time::{Duration, Instant},
};

pub struct TaskState {
    executor: TaskExecutor,
    map_scheduler: MapScheduler,
    map_results: Arc<MapResultQueue>,
    map_poll: MapPollCoordinator,
}

impl Default for TaskState {
    fn default() -> Self {
        let map_results = Arc::new(MapResultQueue::default());
        let map_scheduler = MapScheduler::with_results(Arc::clone(&map_results))
            .unwrap_or_else(|_| MapScheduler::unavailable(Arc::clone(&map_results)));
        Self {
            executor: TaskExecutor::default(),
            map_scheduler,
            map_results,
            map_poll: MapPollCoordinator::default(),
        }
    }
}

impl TaskState {
    pub fn submit<T, F>(&self, session_id: u64, work: F) -> Result<JobReceipt<T>, ExecutorError>
    where
        T: Send + 'static,
        F: FnOnce(TaskHandle) -> Result<T, CoreError> + Send + 'static,
    {
        self.executor.submit(session_id, work)
    }

    pub fn cancel_current(&self) -> bool {
        self.executor.cancel_current()
    }

    pub fn begin_map_viewport(
        &self,
        session_id: u64,
        retained: &HashSet<MapJobKey>,
    ) -> Result<u64, MapSchedulerError> {
        self.map_scheduler.begin_viewport(session_id, retained)
    }

    pub fn begin_map_viewport_with_background(
        &self,
        session_id: u64,
        visible: &HashSet<MapJobKey>,
        background: &HashSet<MapJobKey>,
    ) -> Result<u64, MapSchedulerError> {
        self.map_scheduler
            .begin_viewport_with_background(session_id, visible, background)
    }

    pub fn submit_map<F>(
        &self,
        viewport_id: u64,
        key: MapJobKey,
        priority: MapPriority,
        work: F,
    ) -> Result<MapSubmitOutcome, MapSchedulerError>
    where
        F: FnOnce(MapTaskHandle) + Send + 'static,
    {
        self.map_scheduler.submit(viewport_id, key, priority, work)
    }

    pub fn map_results(&self) -> Arc<MapResultQueue> {
        Arc::clone(&self.map_results)
    }

    pub fn track_map_viewport(
        &self,
        session_id: u64,
        viewport_id: u64,
        target_count: usize,
        prefetch: Vec<MapJobKey>,
    ) {
        self.map_poll.track(
            session_id,
            viewport_id,
            target_count,
            prefetch,
            &self.map_results,
        );
    }

    pub fn begin_map_poll(
        &self,
        session_id: u64,
        viewport_id: u64,
    ) -> Result<MapPollRequest, MapPollError> {
        self.map_poll
            .begin(session_id, viewport_id, Arc::clone(&self.map_results))
    }

    pub fn abort_map_viewport(&self, session_id: u64, viewport_id: u64) {
        self.map_scheduler.abort_viewport(session_id, viewport_id);
        self.map_poll
            .invalidate(session_id, viewport_id, &self.map_results);
    }

    pub fn abort_map_other_sessions(&self, current_session_id: u64) {
        if let Some((session_id, viewport_id)) = self.map_scheduler.current_viewport() {
            if session_id != current_session_id {
                self.abort_map_viewport(session_id, viewport_id);
            }
        }
    }

    pub fn shutdown(&self, timeout: Duration) -> bool {
        let deadline = Instant::now().checked_add(timeout);
        self.map_poll.shutdown();
        self.map_results.begin_shutdown();
        self.map_scheduler.begin_shutdown();
        self.executor.begin_shutdown();
        let map_stopped = self.map_scheduler.await_shutdown(remaining(deadline));
        let executor_stopped = self.executor.await_shutdown(remaining(deadline));
        map_stopped && executor_stopped
    }
}

fn remaining(deadline: Option<Instant>) -> Duration {
    deadline
        .and_then(|end| end.checked_duration_since(Instant::now()))
        .unwrap_or(Duration::ZERO)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{mpsc, Arc, Condvar, Mutex, MutexGuard};

    #[test]
    fn shutdown_closes_both_task_domains_before_waiting() {
        let state = TaskState::default();
        let viewport = state.begin_map_viewport(1, &HashSet::new()).unwrap();
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();

        for tile_x in [1, 2] {
            let release = Arc::clone(&release);
            let started = started_tx.clone();
            state
                .submit_map(
                    viewport,
                    MapJobKey::new(1, tile_x, 0, 1).unwrap(),
                    MapPriority::visible(0, tile_x, 0),
                    move |_| {
                        started.send(()).unwrap();
                        wait_until_released(&release);
                    },
                )
                .unwrap();
        }

        let release_executor = Arc::clone(&release);
        let started_executor = started_tx.clone();
        let receipt = state
            .submit(1, move |_| {
                started_executor.send(()).unwrap();
                wait_until_released(&release_executor);
                Ok(())
            })
            .unwrap();
        for _ in 0..3 {
            started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        }

        assert!(!state.shutdown(Duration::from_millis(5)));
        assert!(matches!(
            state.submit(1, |_| Ok(())),
            Err(ExecutorError::ShuttingDown)
        ));
        assert_eq!(
            state.submit_map(
                viewport,
                MapJobKey::new(1, 3, 0, 1).unwrap(),
                MapPriority::visible(0, 3, 0),
                |_| {}
            ),
            Err(MapSchedulerError::ShuttingDown)
        );

        let (gate, changed) = &*release;
        *lock(gate) = true;
        changed.notify_all();
        assert!(state.shutdown(Duration::from_secs(1)));
        assert!(matches!(
            receipt.wait(),
            Err(ExecutorError::Task(TaskError::Cancelled))
        ));
    }

    #[test]
    fn map_poll_is_single_inflight_and_new_viewport_wakes_the_old_waiter() {
        let state = Arc::new(TaskState::default());
        state.track_map_viewport(1, 1, 1, Vec::new());
        let first = state.begin_map_poll(1, 1).unwrap();
        assert!(matches!(
            state.begin_map_poll(1, 1),
            Err(MapPollError::Invalid)
        ));
        let (done_tx, done_rx) = mpsc::channel();
        let waiter = std::thread::spawn(move || {
            done_tx.send(first.wait(Duration::from_secs(2))).unwrap();
        });
        state.track_map_viewport(1, 2, 1, Vec::new());
        assert!(matches!(
            done_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            Err(MapPollError::Outdated)
        ));
        waiter.join().unwrap();
        assert!(state.begin_map_poll(1, 2).is_ok());
    }

    #[test]
    fn cancelling_exact_map_viewport_wakes_waiter_without_cancelling_newer_viewport() {
        let state = Arc::new(TaskState::default());
        let first = state.begin_map_viewport(1, &HashSet::new()).unwrap();
        state.track_map_viewport(1, first, 1, Vec::new());
        let poll = state.begin_map_poll(1, first).unwrap();
        let (done_tx, done_rx) = mpsc::channel();
        let waiter = std::thread::spawn(move || {
            done_tx.send(poll.wait(Duration::from_secs(2))).unwrap();
        });
        state.abort_map_viewport(1, first);
        assert!(matches!(
            done_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            Err(MapPollError::Outdated)
        ));
        waiter.join().unwrap();

        let second = state.begin_map_viewport(1, &HashSet::new()).unwrap();
        state.track_map_viewport(1, second, 1, Vec::new());
        state.abort_map_viewport(1, first);
        assert!(state.begin_map_poll(1, second).is_ok());
        state.abort_map_viewport(1, second);
        assert!(matches!(
            state.begin_map_poll(1, second),
            Err(MapPollError::Outdated)
        ));
    }

    fn wait_until_released(release: &Arc<(Mutex<bool>, Condvar)>) {
        let (gate, changed) = &**release;
        let guard = lock(gate);
        drop(
            changed
                .wait_while(guard, |released| !*released)
                .unwrap_or_else(|poisoned| poisoned.into_inner()),
        );
    }

    fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
        mutex
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}
