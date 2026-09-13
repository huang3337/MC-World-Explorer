mod executor;
mod registry;

pub use executor::{ExecutorError, JobReceipt, TaskExecutor};
pub use registry::{TaskError, TaskHandle, TaskRegistry};

use mcwe_core::CoreError;
use std::time::Duration;

#[derive(Default)]
pub struct TaskState {
    executor: TaskExecutor,
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

    pub fn shutdown(&self, timeout: Duration) -> bool {
        self.executor.shutdown(timeout)
    }
}
