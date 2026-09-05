use std::sync::{
    atomic::{AtomicU8, Ordering},
    Arc,
};

const RUNNING: u8 = 0;
const CANCELLED: u8 = 1;
const ACCEPTED: u8 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskError {
    Cancelled,
    Outdated,
    IdExhausted,
}

/// 可交给工作线程的只读身份与协作取消令牌；不负责启动或强杀线程。
#[derive(Debug, Clone)]
pub struct TaskHandle {
    id: u64,
    state: Arc<AtomicU8>,
}

impl TaskHandle {
    pub fn id(&self) -> u64 {
        self.id
    }

    pub fn cancel(&self) {
        // 取消与结果接纳竞争同一原子状态，已经接纳的结果不会被事后改为取消。
        let _ = self
            .state
            .compare_exchange(RUNNING, CANCELLED, Ordering::SeqCst, Ordering::SeqCst);
    }

    pub fn is_cancelled(&self) -> bool {
        self.state.load(Ordering::SeqCst) == CANCELLED
    }
}

/// 单槽位登记器；编号在本登记器生命周期内单调递增，不是跨登记器全局 ID。
/// begin/accept 需要独占访问，工作线程只拿 TaskHandle；无队列、执行器或锁。
#[derive(Debug, Default)]
pub struct TaskRegistry {
    last_id: u64,
    current: Option<TaskHandle>,
}

impl TaskRegistry {
    pub fn begin(&mut self) -> Result<TaskHandle, TaskError> {
        let id = self.last_id.checked_add(1).ok_or(TaskError::IdExhausted)?;
        if let Some(previous) = self.current.take() {
            previous.cancel();
        }
        let handle = TaskHandle {
            id,
            state: Arc::new(AtomicU8::new(RUNNING)),
        };
        self.last_id = id;
        self.current = Some(handle.clone());
        Ok(handle)
    }

    /// 接纳时同时验证槽位身份和取消状态，且只能成功接纳一次。
    /// CAS 是取消/接纳的线性化点。调用方仍须在实际提交界面状态时协调
    /// 新请求；本接口不保证已返回的值在未来任意时刻仍属于最新任务。
    pub fn accept<T>(&mut self, task: &TaskHandle, result: T) -> Result<T, TaskError> {
        if !self
            .current
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(&current.state, &task.state))
        {
            return Err(TaskError::Outdated);
        }
        let state =
            task.state
                .compare_exchange(RUNNING, ACCEPTED, Ordering::SeqCst, Ordering::SeqCst);
        self.current = None;
        match state {
            Ok(_) => Ok(result),
            Err(CANCELLED) => Err(TaskError::Cancelled),
            Err(_) => Err(TaskError::Outdated),
        }
    }
}

impl Drop for TaskRegistry {
    fn drop(&mut self) {
        if let Some(task) = &self.current {
            task.cancel();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_task_supersedes_previous_and_ids_increase() {
        let mut registry = TaskRegistry::default();
        let first = registry.begin().unwrap();
        let second = registry.begin().unwrap();
        assert!(second.id() > first.id());
        assert!(first.is_cancelled());
        assert_eq!(registry.accept(&first, 1), Err(TaskError::Outdated));
        assert_eq!(registry.accept(&second, 2), Ok(2));
        assert_eq!(registry.accept(&second, 3), Err(TaskError::Outdated));
    }

    #[test]
    fn cancellation_is_idempotent_and_shared() {
        let mut registry = TaskRegistry::default();
        let task = registry.begin().unwrap();
        let worker = task.clone();
        worker.cancel();
        worker.cancel();
        assert!(task.is_cancelled());
        assert_eq!(registry.accept(&task, "result"), Err(TaskError::Cancelled));
    }

    #[test]
    fn tokens_from_another_registry_cannot_publish_results() {
        let mut first = TaskRegistry::default();
        let mut second = TaskRegistry::default();
        let task = first.begin().unwrap();
        let foreign = second.begin().unwrap();
        assert_eq!(task.id(), foreign.id());
        assert_eq!(first.accept(&foreign, ()), Err(TaskError::Outdated));
        assert_eq!(first.accept(&task, ()), Ok(()));
    }

    #[test]
    fn concurrent_readers_observe_cancellation_without_locks() {
        let mut registry = TaskRegistry::default();
        let task = registry.begin().unwrap();
        std::thread::scope(|scope| {
            let readers: Vec<_> = (0..8)
                .map(|_| {
                    let token = task.clone();
                    scope.spawn(move || {
                        for _ in 0..1000 {
                            let _ = token.is_cancelled();
                        }
                        token
                    })
                })
                .collect();
            task.cancel();
            for reader in readers {
                assert!(reader.join().unwrap().is_cancelled());
            }
        });
        assert_eq!(registry.accept(&task, ()), Err(TaskError::Cancelled));
    }

    #[test]
    fn exhaustion_does_not_wrap_or_cancel_current_task() {
        let mut registry = TaskRegistry::default();
        let task = registry.begin().unwrap();
        registry.last_id = u64::MAX;
        assert!(matches!(registry.begin(), Err(TaskError::IdExhausted)));
        assert!(!task.is_cancelled());
        assert_eq!(registry.accept(&task, ()), Ok(()));
    }

    #[test]
    fn dropping_registry_signals_workers_to_stop() {
        let task = {
            let mut registry = TaskRegistry::default();
            registry.begin().unwrap()
        };
        assert!(task.is_cancelled());
    }

    #[test]
    fn concurrent_cancel_and_accept_have_one_terminal_outcome() {
        for _ in 0..32 {
            let mut registry = TaskRegistry::default();
            let task = registry.begin().unwrap();
            std::thread::scope(|scope| {
                let barrier = Arc::new(std::sync::Barrier::new(2));
                let worker_barrier = barrier.clone();
                let worker = task.clone();
                let cancellation = scope.spawn(move || {
                    worker_barrier.wait();
                    worker.cancel();
                });
                barrier.wait();
                let accepted = registry.accept(&task, ());
                cancellation.join().unwrap();
                match accepted {
                    Ok(()) => assert!(!task.is_cancelled()),
                    Err(TaskError::Cancelled) => assert!(task.is_cancelled()),
                    other => panic!("unexpected result: {other:?}"),
                }
            });
        }
    }
}
