use super::{MapDrainOutcome, MapJobKey, MapResultItem, MapResultPayload, MapResultQueue};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, MutexGuard,
    },
    time::Duration,
};

#[derive(Default)]
pub struct MapPollCoordinator {
    tracker: Arc<Mutex<MapPollTracker>>,
}

#[derive(Default)]
struct MapPollTracker {
    current: Option<MapPollCursor>,
}

struct MapPollCursor {
    session_id: u64,
    viewport_id: u64,
    target_count: usize,
    terminal_count: usize,
    next_sequence: u64,
    polling: bool,
    outdated: Arc<AtomicBool>,
    prefetch: Vec<MapJobKey>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapPollError {
    Invalid,
    Outdated,
    ShuttingDown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapPollState {
    Items,
    Timeout,
    Ended,
    ShuttingDown,
}

#[derive(Debug)]
pub struct MapPollBatch {
    pub sequence: u64,
    pub items: Vec<MapResultItem>,
    pub has_more: bool,
    pub state: MapPollState,
    pub prefetch: Vec<MapJobKey>,
}

pub struct MapPollRequest {
    tracker: Arc<Mutex<MapPollTracker>>,
    results: Arc<MapResultQueue>,
    session_id: u64,
    viewport_id: u64,
    sequence: u64,
    outdated: Arc<AtomicBool>,
    already_ended: bool,
    finished: bool,
}

impl MapPollCoordinator {
    pub fn track(
        &self,
        session_id: u64,
        viewport_id: u64,
        target_count: usize,
        prefetch: Vec<MapJobKey>,
        results: &MapResultQueue,
    ) {
        let mut tracker = lock(&self.tracker);
        if let Some(previous) = tracker.current.take() {
            previous.outdated.store(true, Ordering::SeqCst);
        }
        tracker.current = Some(MapPollCursor {
            session_id,
            viewport_id,
            target_count,
            terminal_count: 0,
            next_sequence: 1,
            polling: false,
            outdated: Arc::new(AtomicBool::new(false)),
            prefetch,
        });
        drop(tracker);
        results.wake_waiters();
    }

    pub fn begin(
        &self,
        session_id: u64,
        viewport_id: u64,
        results: Arc<MapResultQueue>,
    ) -> Result<MapPollRequest, MapPollError> {
        let mut tracker = lock(&self.tracker);
        let cursor = tracker.current.as_mut().ok_or(MapPollError::Outdated)?;
        if cursor.session_id != session_id || cursor.viewport_id != viewport_id {
            return Err(MapPollError::Outdated);
        }
        if cursor.polling || cursor.next_sequence > crate::protocol::MAX_SAFE_INTEGER_ID {
            return Err(MapPollError::Invalid);
        }
        let sequence = cursor.next_sequence;
        cursor.next_sequence += 1;
        cursor.polling = true;
        Ok(MapPollRequest {
            tracker: Arc::clone(&self.tracker),
            results,
            session_id,
            viewport_id,
            sequence,
            outdated: Arc::clone(&cursor.outdated),
            already_ended: cursor.terminal_count == cursor.target_count,
            finished: false,
        })
    }

    pub fn invalidate(&self, session_id: u64, viewport_id: u64, results: &MapResultQueue) {
        let mut tracker = lock(&self.tracker);
        if tracker.current.as_ref().is_some_and(|cursor| {
            cursor.session_id == session_id && cursor.viewport_id == viewport_id
        }) {
            if let Some(current) = tracker.current.take() {
                current.outdated.store(true, Ordering::SeqCst);
            }
        }
        drop(tracker);
        results.wake_waiters();
    }

    pub fn shutdown(&self) {
        if let Some(current) = lock(&self.tracker).current.take() {
            current.outdated.store(true, Ordering::SeqCst);
        }
    }
}

impl MapPollRequest {
    pub fn wait(mut self, timeout: Duration) -> Result<MapPollBatch, MapPollError> {
        if self.already_ended {
            let mut tracker = lock(&self.tracker);
            let cursor = tracker.current.as_mut().ok_or(MapPollError::Outdated)?;
            if cursor.session_id != self.session_id
                || cursor.viewport_id != self.viewport_id
                || self.outdated.load(Ordering::SeqCst)
            {
                self.finished = true;
                return Err(MapPollError::Outdated);
            }
            cursor.polling = false;
            self.finished = true;
            return Ok(MapPollBatch {
                sequence: self.sequence,
                items: Vec::new(),
                has_more: false,
                state: MapPollState::Ended,
                prefetch: Vec::new(),
            });
        }
        let drained = self.results.wait_and_drain_for_viewport(
            crate::protocol::MAP_BATCH_MAX_ITEMS,
            crate::protocol::MAP_BATCH_MAX_BYTES,
            timeout,
            &self.outdated,
            self.session_id,
            self.viewport_id,
        );
        let mut tracker = lock(&self.tracker);
        let cursor = tracker.current.as_mut().ok_or(MapPollError::Outdated)?;
        if cursor.session_id != self.session_id
            || cursor.viewport_id != self.viewport_id
            || self.outdated.load(Ordering::SeqCst)
        {
            self.finished = true;
            return Err(MapPollError::Outdated);
        }
        cursor.polling = false;
        self.finished = true;
        match drained.outcome {
            MapDrainOutcome::Outdated => Err(MapPollError::Outdated),
            MapDrainOutcome::ShuttingDown => Ok(self.empty(MapPollState::ShuttingDown)),
            MapDrainOutcome::Timeout => Ok(self.empty(MapPollState::Timeout)),
            MapDrainOutcome::Items => {
                if drained.items.iter().any(|item| {
                    item.identity.session_id != self.session_id
                        || item.identity.viewport_id != self.viewport_id
                }) {
                    return Err(MapPollError::Invalid);
                }
                let terminal = drained
                    .items
                    .iter()
                    .filter(|item| !matches!(item.payload, MapResultPayload::Partial(_)))
                    .count();
                cursor.terminal_count = cursor
                    .terminal_count
                    .checked_add(terminal)
                    .ok_or(MapPollError::Invalid)?;
                if cursor.terminal_count > cursor.target_count {
                    return Err(MapPollError::Invalid);
                }
                let ended = cursor.terminal_count == cursor.target_count && !drained.has_more;
                let prefetch = if ended {
                    std::mem::take(&mut cursor.prefetch)
                } else {
                    Vec::new()
                };
                Ok(MapPollBatch {
                    sequence: self.sequence,
                    items: drained.items,
                    has_more: drained.has_more,
                    state: if ended {
                        MapPollState::Ended
                    } else {
                        MapPollState::Items
                    },
                    prefetch,
                })
            }
        }
    }

    fn empty(&self, state: MapPollState) -> MapPollBatch {
        MapPollBatch {
            sequence: self.sequence,
            items: Vec::new(),
            has_more: false,
            state,
            prefetch: Vec::new(),
        }
    }
}

impl Drop for MapPollRequest {
    fn drop(&mut self) {
        if self.finished {
            return;
        }
        let mut tracker = lock(&self.tracker);
        if let Some(cursor) = tracker.current.as_mut().filter(|cursor| {
            cursor.session_id == self.session_id && cursor.viewport_id == self.viewport_id
        }) {
            cursor.polling = false;
        }
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
    use crate::tasks::{MapFailureKind, MapResultIdentity};
    use mcwe_core::cancel::NeverCancel;

    #[test]
    fn old_poll_cannot_consume_new_viewport_result_before_tracker_switches() {
        let results = Arc::new(MapResultQueue::default());
        let coordinator = MapPollCoordinator::default();
        results.activate_viewport(1, 1).unwrap();
        coordinator.track(1, 1, 1, Vec::new(), &results);
        let old_poll = coordinator.begin(1, 1, Arc::clone(&results)).unwrap();

        results.activate_viewport(1, 2).unwrap();
        results
            .push_terminal(
                MapResultItem {
                    identity: MapResultIdentity::new(1, 2, MapJobKey::new(1, 0, 0, 1).unwrap())
                        .unwrap(),
                    payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
                },
                &NeverCancel,
            )
            .unwrap();

        assert!(matches!(
            old_poll.wait(Duration::ZERO),
            Err(MapPollError::Outdated)
        ));
        assert_eq!(results.drain(1, usize::MAX).len(), 1);
    }

    #[test]
    fn prefetch_is_released_once_after_visible_targets_end() {
        let results = Arc::new(MapResultQueue::default());
        let coordinator = MapPollCoordinator::default();
        let visible = MapJobKey::new(1, 0, 0, 1).unwrap();
        let prefetch = MapJobKey::new(1, 1, 0, 1).unwrap();
        coordinator.track(1, 1, 1, vec![prefetch], &results);
        results
            .push_terminal(
                MapResultItem {
                    identity: MapResultIdentity::new(1, 1, visible).unwrap(),
                    payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
                },
                &NeverCancel,
            )
            .unwrap();

        let ended = coordinator
            .begin(1, 1, Arc::clone(&results))
            .unwrap()
            .wait(Duration::ZERO)
            .unwrap();
        assert_eq!(ended.state, MapPollState::Ended);
        assert_eq!(ended.prefetch, vec![prefetch]);

        let repeated = coordinator
            .begin(1, 1, results)
            .unwrap()
            .wait(Duration::ZERO)
            .unwrap();
        assert_eq!(repeated.state, MapPollState::Ended);
        assert!(repeated.prefetch.is_empty());
    }
}
