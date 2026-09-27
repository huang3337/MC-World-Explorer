use super::{TaskError, TaskHandle, TaskRegistry};
use mcwe_core::CoreError;
use std::{
    any::Any,
    marker::PhantomData,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{mpsc, Arc, Condvar, Mutex, MutexGuard},
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

type ErasedValue = Box<dyn Any + Send>;
type JobResult = Result<ErasedValue, ExecutorError>;
type Work = Box<dyn FnOnce(TaskHandle) -> Result<ErasedValue, CoreError> + Send>;

#[derive(Debug)]
pub enum ExecutorError {
    Task(TaskError),
    Core(CoreError),
    WorkerUnavailable,
    WorkerPanicked,
    ShuttingDown,
    ResultChannelClosed,
}

struct Job {
    handle: TaskHandle,
    work: Work,
    result: mpsc::Sender<JobResult>,
}

#[derive(Default)]
struct ExecutorState {
    registry: TaskRegistry,
    active: Option<TaskHandle>,
    pending: Option<Job>,
    shutting_down: bool,
    worker_exited: bool,
}

struct Shared {
    state: Mutex<ExecutorState>,
    changed: Condvar,
}

struct WorkerExit(Arc<Shared>);

impl Drop for WorkerExit {
    fn drop(&mut self) {
        let mut state = lock(&self.0.state);
        state.worker_exited = true;
        self.0.changed.notify_all();
    }
}

pub struct TaskExecutor {
    shared: Arc<Shared>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

pub struct JobReceipt<T> {
    task_id: u64,
    receiver: mpsc::Receiver<JobResult>,
    output: PhantomData<T>,
}

impl<T: Send + 'static> JobReceipt<T> {
    pub fn task_id(&self) -> u64 {
        self.task_id
    }

    pub fn wait(self) -> Result<T, ExecutorError> {
        let value = self
            .receiver
            .recv()
            .map_err(|_| ExecutorError::ResultChannelClosed)??;
        value
            .downcast::<T>()
            .map(|value| *value)
            .map_err(|_| ExecutorError::ResultChannelClosed)
    }
}

impl Default for TaskExecutor {
    fn default() -> Self {
        Self::new().unwrap_or_else(|_| Self::unavailable())
    }
}

impl TaskExecutor {
    pub fn new() -> std::io::Result<Self> {
        let shared = Arc::new(Shared {
            state: Mutex::new(ExecutorState::default()),
            changed: Condvar::new(),
        });
        let worker_shared = Arc::clone(&shared);
        let worker = thread::Builder::new()
            .name(String::from("mcwe-heavy-worker"))
            .spawn(move || worker_loop(worker_shared))?;
        Ok(Self {
            shared,
            worker: Mutex::new(Some(worker)),
        })
    }

    fn unavailable() -> Self {
        let state = ExecutorState {
            worker_exited: true,
            ..ExecutorState::default()
        };
        Self {
            shared: Arc::new(Shared {
                state: Mutex::new(state),
                changed: Condvar::new(),
            }),
            worker: Mutex::new(None),
        }
    }

    pub fn submit<T, F>(&self, session_id: u64, work: F) -> Result<JobReceipt<T>, ExecutorError>
    where
        T: Send + 'static,
        F: FnOnce(TaskHandle) -> Result<T, CoreError> + Send + 'static,
    {
        let (sender, receiver) = mpsc::channel();
        let mut state = lock(&self.shared.state);
        if state.worker_exited {
            return Err(ExecutorError::WorkerUnavailable);
        }
        if state.shutting_down {
            return Err(ExecutorError::ShuttingDown);
        }
        let handle = state
            .registry
            .begin(session_id)
            .map_err(ExecutorError::Task)?;
        let task_id = handle.id();
        let replaced = state.pending.replace(Job {
            handle,
            work: Box::new(move |handle| work(handle).map(|value| Box::new(value) as ErasedValue)),
            result: sender,
        });
        drop(state);
        if let Some(replaced) = replaced {
            let _ = replaced
                .result
                .send(Err(ExecutorError::Task(TaskError::Outdated)));
        }
        self.shared.changed.notify_one();
        Ok(JobReceipt {
            task_id,
            receiver,
            output: PhantomData,
        })
    }

    pub fn cancel_current(&self) -> bool {
        let mut state = lock(&self.shared.state);
        if let Some(pending) = state.pending.take() {
            state.registry.discard(&pending.handle);
            drop(state);
            let _ = pending
                .result
                .send(Err(ExecutorError::Task(TaskError::Cancelled)));
            return true;
        }
        state.registry.cancel_current()
    }

    pub fn shutdown(&self, timeout: Duration) -> bool {
        self.begin_shutdown();
        self.await_shutdown(timeout)
    }

    pub(crate) fn begin_shutdown(&self) {
        let pending = self.take_pending_for_shutdown();
        if let Some(pending) = pending {
            let _ = pending.result.send(Err(ExecutorError::ShuttingDown));
        }
    }

    pub(crate) fn await_shutdown(&self, timeout: Duration) -> bool {
        let deadline = Instant::now().checked_add(timeout);
        let mut state = lock(&self.shared.state);
        while !state.worker_exited {
            let Some(remaining) =
                deadline.and_then(|end| end.checked_duration_since(Instant::now()))
            else {
                return false;
            };
            let waited = self.shared.changed.wait_timeout(state, remaining);
            let (next, timeout_result) = waited.unwrap_or_else(|poisoned| poisoned.into_inner());
            state = next;
            if timeout_result.timed_out() && !state.worker_exited {
                return false;
            }
        }
        drop(state);
        self.join_worker()
    }

    fn take_pending_for_shutdown(&self) -> Option<Job> {
        let mut state = lock(&self.shared.state);
        state.shutting_down = true;
        if let Some(active) = &state.active {
            active.cancel();
        }
        let pending = state.pending.take();
        if let Some(pending) = &pending {
            pending.handle.cancel();
            state.registry.discard(&pending.handle);
        } else {
            state.registry.cancel_current();
        }
        drop(state);
        self.shared.changed.notify_all();
        pending
    }

    fn join_worker(&self) -> bool {
        let worker = lock(&self.worker).take();
        worker.is_none_or(|worker| worker.join().is_ok())
    }
}

impl Drop for TaskExecutor {
    fn drop(&mut self) {
        self.begin_shutdown();
        let _ = self.join_worker();
    }
}

fn worker_loop(shared: Arc<Shared>) {
    let _exit = WorkerExit(Arc::clone(&shared));
    loop {
        let job = {
            let mut state = lock(&shared.state);
            while state.pending.is_none() && !state.shutting_down {
                state = shared
                    .changed
                    .wait(state)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            if state.shutting_down {
                return;
            }
            let Some(job) = state.pending.take() else {
                continue;
            };
            state.active = Some(job.handle.clone());
            job
        };

        let computed = match catch_unwind(AssertUnwindSafe(|| (job.work)(job.handle.clone()))) {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(error)) => Err(ExecutorError::Core(error)),
            Err(_) => Err(ExecutorError::WorkerPanicked),
        };
        let terminal = {
            let mut state = lock(&shared.state);
            state.active = None;
            let accepted = state
                .registry
                .accept(&job.handle, job.handle.session_id(), computed);
            shared.changed.notify_all();
            match accepted {
                Ok(result) => result,
                Err(error) => Err(ExecutorError::Task(error)),
            }
        };
        let _ = job.result.send(terminal);
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
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn three_requests_keep_one_worker_and_only_latest_pending() {
        let executor = TaskExecutor::default();
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let active_first = Arc::clone(&active);
        let peak_first = Arc::clone(&peak);
        let first = executor
            .submit(7, move |_| {
                let current = active_first.fetch_add(1, Ordering::SeqCst) + 1;
                peak_first.fetch_max(current, Ordering::SeqCst);
                started_tx.send(()).unwrap();
                release_rx.recv().unwrap();
                active_first.fetch_sub(1, Ordering::SeqCst);
                Ok(1_u32)
            })
            .unwrap();
        started_rx.recv().unwrap();
        let second = executor.submit(7, |_| Ok(2_u32)).unwrap();
        let active_third = Arc::clone(&active);
        let peak_third = Arc::clone(&peak);
        let third = executor
            .submit(7, move |_| {
                let current = active_third.fetch_add(1, Ordering::SeqCst) + 1;
                peak_third.fetch_max(current, Ordering::SeqCst);
                active_third.fetch_sub(1, Ordering::SeqCst);
                Ok(3_u32)
            })
            .unwrap();
        assert!(matches!(
            second.wait(),
            Err(ExecutorError::Task(TaskError::Outdated))
        ));
        release_tx.send(()).unwrap();
        assert!(matches!(
            first.wait(),
            Err(ExecutorError::Task(TaskError::Outdated))
        ));
        assert_eq!(third.wait().unwrap(), 3);
        assert_eq!(peak.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn explicit_cancel_is_idempotent_and_cooperative() {
        let executor = TaskExecutor::default();
        let receipt = executor
            .submit(9, |task| {
                while !task.is_cancelled() {
                    thread::yield_now();
                }
                Err::<(), _>(CoreError::Cancelled)
            })
            .unwrap();
        assert!(executor.cancel_current());
        let _ = executor.cancel_current();
        assert!(matches!(
            receipt.wait(),
            Err(ExecutorError::Task(TaskError::Cancelled))
        ));
        assert!(!executor.cancel_current());
    }

    #[test]
    fn cancel_and_complete_have_one_terminal_outcome() {
        for _ in 0..16 {
            let executor = TaskExecutor::default();
            let barrier = Arc::new(std::sync::Barrier::new(2));
            let worker_barrier = Arc::clone(&barrier);
            let receipt = executor
                .submit(3, move |_| {
                    worker_barrier.wait();
                    Ok(1_u8)
                })
                .unwrap();
            barrier.wait();
            let _ = executor.cancel_current();
            match receipt.wait() {
                Ok(1) | Err(ExecutorError::Task(TaskError::Cancelled)) => {}
                other => panic!("unexpected terminal outcome: {other:?}"),
            }
        }
    }

    #[test]
    fn executor_propagates_id_exhaustion_without_starting_work() {
        let executor = TaskExecutor::default();
        lock(&executor.shared.state).registry.exhaust_ids();
        assert!(matches!(
            executor.submit(1, |_| Ok(())),
            Err(ExecutorError::Task(TaskError::IdExhausted))
        ));
    }

    #[test]
    fn worker_panic_is_isolated_and_next_job_runs() {
        let executor = TaskExecutor::default();
        let failed = executor
            .submit::<(), _>(1, |_| panic!("synthetic worker panic"))
            .unwrap();
        assert!(matches!(failed.wait(), Err(ExecutorError::WorkerPanicked)));
        let recovered = executor.submit(1, |_| Ok(42_u32)).unwrap();
        assert_eq!(recovered.wait().unwrap(), 42);
    }

    #[test]
    fn unavailable_executor_fails_without_panicking() {
        let executor = TaskExecutor::unavailable();
        assert!(matches!(
            executor.submit(1, |_| Ok(())),
            Err(ExecutorError::WorkerUnavailable)
        ));
        assert!(!executor.cancel_current());
        assert!(executor.shutdown(Duration::from_millis(1)));
    }

    #[test]
    fn poisoned_state_lock_recovers() {
        let executor = Arc::new(TaskExecutor::default());
        let shared = Arc::clone(&executor.shared);
        let _ = thread::spawn(move || {
            let _guard = shared.state.lock().unwrap();
            panic!("poison executor state");
        })
        .join();
        assert_eq!(
            executor.submit(1, |_| Ok(5_u32)).unwrap().wait().unwrap(),
            5
        );
    }

    #[test]
    fn bounded_shutdown_rejects_new_work_and_can_finish_later() {
        let executor = TaskExecutor::default();
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let receipt = executor
            .submit(1, move |_| {
                started_tx.send(()).unwrap();
                release_rx.recv().unwrap();
                Ok(())
            })
            .unwrap();
        started_rx.recv().unwrap();
        assert!(!executor.shutdown(Duration::from_millis(5)));
        assert!(matches!(
            executor.submit(1, |_| Ok(())),
            Err(ExecutorError::ShuttingDown)
        ));
        release_tx.send(()).unwrap();
        assert!(executor.shutdown(Duration::from_secs(1)));
        assert!(matches!(
            receipt.wait(),
            Err(ExecutorError::Task(TaskError::Cancelled))
        ));
    }
}
