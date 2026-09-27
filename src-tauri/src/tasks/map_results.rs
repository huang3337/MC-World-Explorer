use super::MapJobKey;
use mcwe_core::{
    cancel::Cancellation,
    map::{MapTile, MapTilePatch},
};
use std::{
    collections::{HashSet, VecDeque},
    mem::size_of,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Condvar, Mutex, MutexGuard,
    },
    time::{Duration, Instant},
};

pub const MAP_RESULT_MAX_ITEMS: usize = 64;
pub const MAP_RESULT_MAX_BYTES: usize = 32 * 1024 * 1024;
pub const MAP_RESULT_MAX_ITEM_BYTES: usize = 2 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MapResultIdentity {
    pub session_id: u64,
    pub viewport_id: u64,
    pub key: MapJobKey,
}

impl MapResultIdentity {
    pub fn new(
        session_id: u64,
        viewport_id: u64,
        key: MapJobKey,
    ) -> Result<Self, MapResultQueueError> {
        if session_id == 0
            || viewport_id == 0
            || key.session_id() != session_id
            || viewport_id > crate::protocol::MAX_SAFE_INTEGER_ID
        {
            return Err(MapResultQueueError::InvalidResult);
        }
        Ok(Self {
            session_id,
            viewport_id,
            key,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapFailureKind {
    ReadFailed,
    WorkerPanicked,
    EncodeFailed,
}

#[derive(Debug)]
pub enum MapResultPayload {
    Partial(MapTilePatch),
    Ready(Arc<MapTile>),
    Failed(MapFailureKind),
}

#[derive(Debug)]
pub struct MapResultItem {
    pub identity: MapResultIdentity,
    pub payload: MapResultPayload,
}

impl MapResultItem {
    fn bytes(&self) -> usize {
        let payload = match &self.payload {
            MapResultPayload::Partial(patch) => {
                patch.colors.capacity() * size_of::<u32>()
                    + patch.heights.capacity() * size_of::<i32>()
                    + patch.statuses.capacity() * size_of::<u8>()
                    + size_of::<MapTilePatch>()
            }
            MapResultPayload::Ready(tile) => {
                tile.colors.capacity() * size_of::<u32>()
                    + tile.heights.capacity() * size_of::<i32>()
                    + tile.statuses.capacity() * size_of::<u8>()
                    + size_of::<MapTile>()
            }
            MapResultPayload::Failed(_) => size_of::<MapFailureKind>(),
        };
        payload + size_of::<MapResultIdentity>() + 128
    }

    fn is_partial(&self) -> bool {
        matches!(self.payload, MapResultPayload::Partial(_))
    }

    fn revision(&self) -> Option<u64> {
        match &self.payload {
            MapResultPayload::Partial(patch) => Some(patch.revision),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapResultQueueError {
    InvalidResult,
    OutdatedRevision,
    ItemTooLarge,
    Cancelled,
    ShuttingDown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PartialPushOutcome {
    Queued,
    Replaced,
    DroppedForCapacity,
    DroppedForOutdatedViewport,
}

#[derive(Debug)]
pub struct MapDrainBatch {
    pub items: Vec<MapResultItem>,
    pub has_more: bool,
    pub outcome: MapDrainOutcome,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapDrainOutcome {
    Items,
    Timeout,
    Outdated,
    ShuttingDown,
}

#[derive(Default)]
enum ViewportFence {
    #[default]
    Unbound,
    Active(u64, u64),
    Inactive,
}

impl ViewportFence {
    fn accepts(&self, identity: MapResultIdentity) -> bool {
        match self {
            Self::Unbound => true,
            Self::Active(session_id, viewport_id) => {
                *session_id == identity.session_id && *viewport_id == identity.viewport_id
            }
            Self::Inactive => false,
        }
    }

    fn rejects_poll(&self, session_id: u64, viewport_id: u64) -> bool {
        match self {
            Self::Unbound => false,
            Self::Active(active_session, active_viewport) => {
                *active_session != session_id || *active_viewport != viewport_id
            }
            Self::Inactive => true,
        }
    }
}

#[derive(Default)]
struct QueueState {
    items: VecDeque<MapResultItem>,
    terminal: HashSet<MapResultIdentity>,
    bytes: usize,
    shutting_down: bool,
    viewport: ViewportFence,
}

#[derive(Default)]
pub struct MapResultQueue {
    state: Mutex<QueueState>,
    changed: Condvar,
}

impl MapResultQueue {
    pub fn push_partial(
        &self,
        item: MapResultItem,
    ) -> Result<PartialPushOutcome, MapResultQueueError> {
        validate_item(&item)?;
        if !item.is_partial() {
            return Err(MapResultQueueError::InvalidResult);
        }
        let item_bytes = item.bytes();
        if item_bytes > MAP_RESULT_MAX_ITEM_BYTES {
            return Err(MapResultQueueError::ItemTooLarge);
        }
        let mut state = lock(&self.state);
        if state.shutting_down {
            return Err(MapResultQueueError::ShuttingDown);
        }
        if !state.viewport.accepts(item.identity) {
            return Ok(PartialPushOutcome::DroppedForOutdatedViewport);
        }
        if state.terminal.contains(&item.identity) {
            return Err(MapResultQueueError::OutdatedRevision);
        }
        let existing = state
            .items
            .iter()
            .position(|candidate| candidate.is_partial() && candidate.identity == item.identity);
        let replaced = if let Some(index) = existing {
            if state.items[index].revision() >= item.revision() {
                return Err(MapResultQueueError::OutdatedRevision);
            }
            remove_at(&mut state, index);
            true
        } else {
            false
        };
        evict_partials_until_fits(&mut state, item_bytes, 1);
        if state.items.len() >= MAP_RESULT_MAX_ITEMS
            || state.bytes.saturating_add(item_bytes) > MAP_RESULT_MAX_BYTES
        {
            return Ok(PartialPushOutcome::DroppedForCapacity);
        }
        state.bytes += item_bytes;
        state.items.push_back(item);
        drop(state);
        self.changed.notify_all();
        Ok(if replaced {
            PartialPushOutcome::Replaced
        } else {
            PartialPushOutcome::Queued
        })
    }

    pub fn push_terminal(
        &self,
        item: MapResultItem,
        cancel: &dyn Cancellation,
    ) -> Result<(), MapResultQueueError> {
        validate_item(&item)?;
        if item.is_partial() {
            return Err(MapResultQueueError::InvalidResult);
        }
        let item_bytes = item.bytes();
        if item_bytes > MAP_RESULT_MAX_ITEM_BYTES {
            return Err(MapResultQueueError::ItemTooLarge);
        }
        let mut state = lock(&self.state);
        if state.terminal.contains(&item.identity) {
            return Err(MapResultQueueError::InvalidResult);
        }
        if let Some(index) = state
            .items
            .iter()
            .position(|candidate| candidate.is_partial() && candidate.identity == item.identity)
        {
            remove_at(&mut state, index);
        }
        loop {
            if state.shutting_down {
                return Err(MapResultQueueError::ShuttingDown);
            }
            if !state.viewport.accepts(item.identity) {
                return Err(MapResultQueueError::OutdatedRevision);
            }
            if cancel.is_cancelled() {
                return Err(MapResultQueueError::Cancelled);
            }
            evict_partials_until_fits(&mut state, item_bytes, 1);
            if state.items.len() < MAP_RESULT_MAX_ITEMS
                && state.bytes.saturating_add(item_bytes) <= MAP_RESULT_MAX_BYTES
            {
                state.bytes += item_bytes;
                state.terminal.insert(item.identity);
                state.items.push_back(item);
                drop(state);
                self.changed.notify_all();
                return Ok(());
            }
            let waited = self.changed.wait_timeout(state, Duration::from_millis(50));
            let (next, _) = waited.unwrap_or_else(|poisoned| poisoned.into_inner());
            state = next;
        }
    }

    pub fn drain(&self, max_items: usize, max_bytes: usize) -> Vec<MapResultItem> {
        let mut state = lock(&self.state);
        let mut drained = Vec::new();
        let mut bytes = 0_usize;
        while drained.len() < max_items {
            let Some(front) = state.items.front() else {
                break;
            };
            let item_bytes = front.bytes();
            if !drained.is_empty() && bytes.saturating_add(item_bytes) > max_bytes {
                break;
            }
            let item = state.items.pop_front().expect("front was present");
            state.bytes -= item_bytes;
            bytes += item_bytes;
            drained.push(item);
        }
        drop(state);
        self.changed.notify_all();
        drained
    }

    pub fn wait_and_drain(
        &self,
        max_items: usize,
        max_bytes: usize,
        timeout: Duration,
        outdated: &AtomicBool,
    ) -> MapDrainBatch {
        self.wait_and_drain_inner(max_items, max_bytes, timeout, outdated, None)
    }

    pub(crate) fn wait_and_drain_for_viewport(
        &self,
        max_items: usize,
        max_bytes: usize,
        timeout: Duration,
        outdated: &AtomicBool,
        session_id: u64,
        viewport_id: u64,
    ) -> MapDrainBatch {
        self.wait_and_drain_inner(
            max_items,
            max_bytes,
            timeout,
            outdated,
            Some((session_id, viewport_id)),
        )
    }

    fn wait_and_drain_inner(
        &self,
        max_items: usize,
        max_bytes: usize,
        timeout: Duration,
        outdated: &AtomicBool,
        expected_viewport: Option<(u64, u64)>,
    ) -> MapDrainBatch {
        let deadline = Instant::now().checked_add(timeout);
        let mut state = lock(&self.state);
        let wrong_viewport = |state: &QueueState| {
            expected_viewport.is_some_and(|(session_id, viewport_id)| {
                state.viewport.rejects_poll(session_id, viewport_id)
            })
        };
        while state.items.is_empty()
            && !state.shutting_down
            && !outdated.load(Ordering::SeqCst)
            && !wrong_viewport(&state)
        {
            let Some(remaining) =
                deadline.and_then(|end| end.checked_duration_since(Instant::now()))
            else {
                return MapDrainBatch {
                    items: Vec::new(),
                    has_more: false,
                    outcome: MapDrainOutcome::Timeout,
                };
            };
            let waited = self.changed.wait_timeout(state, remaining);
            let (next, timed) = waited.unwrap_or_else(|poisoned| poisoned.into_inner());
            state = next;
            if timed.timed_out() && state.items.is_empty() && !wrong_viewport(&state) {
                return MapDrainBatch {
                    items: Vec::new(),
                    has_more: false,
                    outcome: MapDrainOutcome::Timeout,
                };
            }
        }
        if state.shutting_down {
            return MapDrainBatch {
                items: Vec::new(),
                has_more: false,
                outcome: MapDrainOutcome::ShuttingDown,
            };
        }
        if outdated.load(Ordering::SeqCst) || wrong_viewport(&state) {
            return MapDrainBatch {
                items: Vec::new(),
                has_more: false,
                outcome: MapDrainOutcome::Outdated,
            };
        }
        let mut items = Vec::new();
        let mut bytes = 0_usize;
        while items.len() < max_items {
            let Some(front) = state.items.front() else {
                break;
            };
            let item_bytes = front.bytes();
            if !items.is_empty() && bytes.saturating_add(item_bytes) > max_bytes {
                break;
            }
            let item = state.items.pop_front().expect("front was present");
            state.bytes -= item_bytes;
            bytes += item_bytes;
            items.push(item);
        }
        let has_more = !state.items.is_empty();
        drop(state);
        self.changed.notify_all();
        MapDrainBatch {
            items,
            has_more,
            outcome: MapDrainOutcome::Items,
        }
    }

    pub(crate) fn activate_viewport(
        &self,
        session_id: u64,
        viewport_id: u64,
    ) -> Result<(), MapResultQueueError> {
        let mut state = lock(&self.state);
        if state.shutting_down {
            return Err(MapResultQueueError::ShuttingDown);
        }
        state.items.clear();
        state.terminal.clear();
        state.bytes = 0;
        state.viewport = ViewportFence::Active(session_id, viewport_id);
        drop(state);
        self.changed.notify_all();
        Ok(())
    }

    pub fn discard_viewport(&self, session_id: u64, viewport_id: u64) {
        let mut state = lock(&self.state);
        if matches!(state.viewport, ViewportFence::Active(active_session, active_viewport)
            if active_session == session_id && active_viewport == viewport_id)
        {
            state.viewport = ViewportFence::Inactive;
        }
        let mut index = 0;
        while index < state.items.len() {
            if state.items[index].identity.session_id == session_id
                && state.items[index].identity.viewport_id == viewport_id
            {
                remove_at(&mut state, index);
            } else {
                index += 1;
            }
        }
        state.terminal.retain(|identity| {
            identity.session_id != session_id || identity.viewport_id != viewport_id
        });
        drop(state);
        self.changed.notify_all();
    }

    pub fn wake_waiters(&self) {
        self.changed.notify_all();
    }

    pub(crate) fn begin_shutdown(&self) {
        let mut state = lock(&self.state);
        state.shutting_down = true;
        state.items.clear();
        state.terminal.clear();
        state.bytes = 0;
        drop(state);
        self.changed.notify_all();
    }
}

fn validate_item(item: &MapResultItem) -> Result<(), MapResultQueueError> {
    match &item.payload {
        MapResultPayload::Partial(patch) => {
            let cells = usize::from(patch.width)
                .checked_mul(usize::from(patch.height))
                .ok_or(MapResultQueueError::InvalidResult)?;
            if patch.revision == 0
                || patch.request.tile_x != item.identity.key.tile_x()
                || patch.request.tile_z != item.identity.key.tile_z()
                || patch.request.blocks_per_pixel != item.identity.key.blocks_per_pixel()
                || patch.colors.len() != cells
                || patch.heights.len() != cells
                || patch.statuses.len() != cells
                || usize::from(patch.pixel_x) + usize::from(patch.width) > 256
                || usize::from(patch.pixel_z) + usize::from(patch.height) > 256
                || patch.completed_chunks > patch.total_chunks
                || patch.statuses.iter().any(|status| *status > 4)
            {
                return Err(MapResultQueueError::InvalidResult);
            }
        }
        MapResultPayload::Ready(tile) => {
            if tile.request.tile_x != item.identity.key.tile_x()
                || tile.request.tile_z != item.identity.key.tile_z()
                || tile.request.blocks_per_pixel != item.identity.key.blocks_per_pixel()
            {
                return Err(MapResultQueueError::InvalidResult);
            }
        }
        MapResultPayload::Failed(_) => {}
    }
    Ok(())
}

fn evict_partials_until_fits(state: &mut QueueState, bytes: usize, items: usize) {
    while state.items.len().saturating_add(items) > MAP_RESULT_MAX_ITEMS
        || state.bytes.saturating_add(bytes) > MAP_RESULT_MAX_BYTES
    {
        let Some(index) = state.items.iter().position(MapResultItem::is_partial) else {
            break;
        };
        remove_at(state, index);
    }
}

fn remove_at(state: &mut QueueState, index: usize) {
    if let Some(item) = state.items.remove(index) {
        state.bytes -= item.bytes();
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
    use mcwe_core::{cancel::NeverCancel, map::MapTileRequest};
    use std::{sync::mpsc, thread, time::Duration};

    fn identity(tile_x: i32) -> MapResultIdentity {
        MapResultIdentity::new(1, 1, MapJobKey::new(1, tile_x, 0, 1).unwrap()).unwrap()
    }

    fn partial(tile_x: i32, revision: u64) -> MapResultItem {
        MapResultItem {
            identity: identity(tile_x),
            payload: MapResultPayload::Partial(MapTilePatch {
                request: MapTileRequest {
                    tile_x,
                    tile_z: 0,
                    blocks_per_pixel: 1,
                },
                revision,
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
        }
    }

    fn failed(tile_x: i32) -> MapResultItem {
        MapResultItem {
            identity: identity(tile_x),
            payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
        }
    }

    #[test]
    fn same_tile_keeps_only_the_latest_partial_revision() {
        let queue = MapResultQueue::default();
        assert_eq!(
            queue.push_partial(partial(1, 1)).unwrap(),
            PartialPushOutcome::Queued
        );
        assert_eq!(
            queue.push_partial(partial(1, 2)).unwrap(),
            PartialPushOutcome::Replaced
        );
        assert_eq!(
            queue.push_partial(partial(1, 2)),
            Err(MapResultQueueError::OutdatedRevision)
        );
        let items = queue.drain(10, usize::MAX);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].revision(), Some(2));
    }

    #[test]
    fn partial_capacity_evicts_oldest_replaceable_item() {
        let queue = MapResultQueue::default();
        for tile_x in 0..=MAP_RESULT_MAX_ITEMS as i32 {
            assert_ne!(
                queue.push_partial(partial(tile_x, 1)).unwrap(),
                PartialPushOutcome::DroppedForCapacity
            );
        }
        let items = queue.drain(usize::MAX, usize::MAX);
        assert_eq!(items.len(), MAP_RESULT_MAX_ITEMS);
        assert!(!items.iter().any(|item| item.identity.key.tile_x() == 0));
        assert!(items
            .iter()
            .any(|item| item.identity.key.tile_x() == MAP_RESULT_MAX_ITEMS as i32));
    }

    #[test]
    fn terminal_waits_for_consumption_instead_of_being_dropped() {
        let queue = Arc::new(MapResultQueue::default());
        for tile_x in 0..MAP_RESULT_MAX_ITEMS as i32 {
            queue.push_terminal(failed(tile_x), &NeverCancel).unwrap();
        }
        let producer = Arc::clone(&queue);
        let (done_tx, done_rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            let result = producer.push_terminal(failed(1000), &NeverCancel);
            done_tx.send(result).unwrap();
        });
        assert!(done_rx.recv_timeout(Duration::from_millis(20)).is_err());
        assert_eq!(queue.drain(1, usize::MAX).len(), 1);
        assert_eq!(
            done_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            Ok(())
        );
        worker.join().unwrap();
        assert!(queue
            .drain(usize::MAX, usize::MAX)
            .iter()
            .any(|item| item.identity.key.tile_x() == 1000));
    }

    #[test]
    fn shutdown_wakes_a_blocked_terminal_producer() {
        let queue = Arc::new(MapResultQueue::default());
        for tile_x in 0..MAP_RESULT_MAX_ITEMS as i32 {
            queue.push_terminal(failed(tile_x), &NeverCancel).unwrap();
        }
        let producer = Arc::clone(&queue);
        let (done_tx, done_rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            done_tx
                .send(producer.push_terminal(failed(1000), &NeverCancel))
                .unwrap();
        });
        assert!(done_rx.recv_timeout(Duration::from_millis(20)).is_err());
        queue.begin_shutdown();
        assert_eq!(
            done_rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            Err(MapResultQueueError::ShuttingDown)
        );
        worker.join().unwrap();
        assert!(queue.drain(usize::MAX, usize::MAX).is_empty());
    }

    #[test]
    fn terminal_replaces_its_queued_partial() {
        let queue = MapResultQueue::default();
        queue.push_partial(partial(7, 1)).unwrap();
        queue.push_terminal(failed(7), &NeverCancel).unwrap();
        let items = queue.drain(10, usize::MAX);
        assert_eq!(items.len(), 1);
        assert!(matches!(items[0].payload, MapResultPayload::Failed(_)));
    }

    #[test]
    fn terminal_is_unique_and_rejects_late_partial_results() {
        let queue = MapResultQueue::default();
        queue.push_terminal(failed(7), &NeverCancel).unwrap();
        assert_eq!(
            queue.push_terminal(failed(7), &NeverCancel),
            Err(MapResultQueueError::InvalidResult)
        );
        assert_eq!(queue.drain(10, usize::MAX).len(), 1);
        assert_eq!(
            queue.push_partial(partial(7, 1)),
            Err(MapResultQueueError::OutdatedRevision)
        );
    }

    #[test]
    fn byte_limit_evicts_partials_before_item_limit() {
        let queue = MapResultQueue::default();
        for tile_x in 0..MAP_RESULT_MAX_ITEMS as i32 {
            let mut item = partial(tile_x, 1);
            let MapResultPayload::Partial(patch) = &mut item.payload else {
                unreachable!()
            };
            patch.width = 256;
            patch.height = 256;
            patch.colors = vec![0; 256 * 256];
            patch.heights = vec![0; 256 * 256];
            patch.statuses = vec![4; 256 * 256];
            queue.push_partial(item).unwrap();
        }
        let state = lock(&queue.state);
        assert!(state.bytes <= MAP_RESULT_MAX_BYTES);
        assert!(state.items.len() < MAP_RESULT_MAX_ITEMS);
        assert!(state
            .items
            .back()
            .is_some_and(|item| item.identity.key.tile_x() == MAP_RESULT_MAX_ITEMS as i32 - 1));
    }
}
