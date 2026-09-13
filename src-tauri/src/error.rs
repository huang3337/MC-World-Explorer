use crate::tasks::{ExecutorError, TaskError};
use mcwe_core::CoreError;
use serde::Serialize;

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    InvalidArguments,
    InvalidPath,
    ReparsePoint,
    NotDirectory,
    NotFile,
    NotFound,
    PermissionDenied,
    InUse,
    UnsupportedPlatform,
    IoError,
    TaskCancelled,
    TaskOutdated,
    TaskIdExhausted,
    InvalidNbt,
    InvalidRegion,
    InvalidChunk,
    UnsupportedCompression,
    UnsupportedChunk,
    ResourceLimit,
    NoActiveWorld,
    SessionOutdated,
    SessionIdExhausted,
    WorkerFailed,
    ShuttingDown,
}

/// 只能使用固定语义，不接受路径、堆栈或任意来源字符串。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorContext {
    ReadWorld,
}

/// 前端错误契约；字段私有，防止调用方注入未经筛选的内部错误文本。
#[derive(Debug, Clone, Serialize)]
pub struct AppError {
    code: ErrorCode,
    message: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    context: Option<ErrorContext>,
}

impl AppError {
    pub(crate) fn new(code: ErrorCode) -> Self {
        let message = match code {
            ErrorCode::InvalidArguments => "请求参数无效",
            ErrorCode::InvalidPath => "路径无效",
            ErrorCode::ReparsePoint => "路径包含不允许跟随的链接或重解析点",
            ErrorCode::NotDirectory => "来源路径不是目录",
            ErrorCode::NotFile => "读取目标不是普通文件",
            ErrorCode::NotFound => "读取来源不存在",
            ErrorCode::PermissionDenied => "没有读取来源的权限",
            ErrorCode::InUse => "来源正在被其他文件操作占用",
            ErrorCode::UnsupportedPlatform => "当前平台尚不支持此操作",
            ErrorCode::IoError => "读取来源时发生 I/O 错误",
            ErrorCode::TaskCancelled => "任务已取消",
            ErrorCode::TaskOutdated => "任务结果已过期",
            ErrorCode::TaskIdExhausted => "任务编号已耗尽，无法启动新任务",
            ErrorCode::InvalidNbt => "NBT 数据无效",
            ErrorCode::InvalidRegion => "Region 文件结构无效",
            ErrorCode::InvalidChunk => "区块数据无效",
            ErrorCode::UnsupportedCompression => "数据使用了不支持的压缩方式",
            ErrorCode::UnsupportedChunk => "区块格式不在当前版本支持范围内",
            ErrorCode::ResourceLimit => "数据超过安全资源上限",
            ErrorCode::NoActiveWorld => "尚未选择有效世界",
            ErrorCode::SessionOutdated => "世界会话已过期",
            ErrorCode::SessionIdExhausted => "世界会话编号已耗尽，无法选择新世界",
            ErrorCode::WorkerFailed => "后台任务异常终止",
            ErrorCode::ShuttingDown => "应用正在关闭，无法启动新任务",
        };
        Self {
            code,
            message,
            context: None,
        }
    }

    pub fn with_context(mut self, context: ErrorContext) -> Self {
        self.context = Some(context);
        self
    }
}

impl From<CoreError> for AppError {
    fn from(error: CoreError) -> Self {
        Self::new(match error {
            CoreError::InvalidPath => ErrorCode::InvalidPath,
            CoreError::ReparsePoint => ErrorCode::ReparsePoint,
            CoreError::NotDirectory => ErrorCode::NotDirectory,
            CoreError::NotFile => ErrorCode::NotFile,
            CoreError::NotFound => ErrorCode::NotFound,
            CoreError::PermissionDenied => ErrorCode::PermissionDenied,
            CoreError::InUse => ErrorCode::InUse,
            CoreError::UnsupportedPlatform => ErrorCode::UnsupportedPlatform,
            CoreError::InvalidNbt => ErrorCode::InvalidNbt,
            CoreError::InvalidRegion => ErrorCode::InvalidRegion,
            CoreError::InvalidChunk => ErrorCode::InvalidChunk,
            CoreError::UnsupportedCompression => ErrorCode::UnsupportedCompression,
            CoreError::UnsupportedChunk => ErrorCode::UnsupportedChunk,
            CoreError::ResourceLimit => ErrorCode::ResourceLimit,
            CoreError::Cancelled => ErrorCode::TaskCancelled,
            CoreError::Io(_) => ErrorCode::IoError,
        })
    }
}

impl From<TaskError> for AppError {
    fn from(error: TaskError) -> Self {
        Self::new(match error {
            TaskError::Cancelled => ErrorCode::TaskCancelled,
            TaskError::Outdated => ErrorCode::TaskOutdated,
            TaskError::IdExhausted => ErrorCode::TaskIdExhausted,
        })
    }
}

impl From<ExecutorError> for AppError {
    fn from(error: ExecutorError) -> Self {
        match error {
            ExecutorError::Task(error) => Self::from(error),
            ExecutorError::Core(error) => Self::from(error),
            ExecutorError::WorkerUnavailable
            | ExecutorError::WorkerPanicked
            | ExecutorError::ResultChannelClosed => Self::new(ErrorCode::WorkerFailed),
            ExecutorError::ShuttingDown => Self::new(ErrorCode::ShuttingDown),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mcwe_core::CoreError;

    #[test]
    fn core_error_codes_are_stable_and_exhaustive() {
        let cases = [
            (CoreError::InvalidPath, "INVALID_PATH"),
            (CoreError::ReparsePoint, "REPARSE_POINT"),
            (CoreError::NotDirectory, "NOT_DIRECTORY"),
            (CoreError::NotFile, "NOT_FILE"),
            (CoreError::NotFound, "NOT_FOUND"),
            (CoreError::PermissionDenied, "PERMISSION_DENIED"),
            (CoreError::InUse, "IN_USE"),
            (CoreError::UnsupportedPlatform, "UNSUPPORTED_PLATFORM"),
            (
                CoreError::Io(std::io::Error::other("private path C:\\secret")),
                "IO_ERROR",
            ),
        ];
        for (error, code) in cases {
            let json = serde_json::to_value(AppError::from(error)).unwrap();
            assert_eq!(json["code"], code);
            assert!(!json["message"].as_str().unwrap().is_ascii());
            assert!(json.get("context").is_none());
            assert!(!json.to_string().contains("secret"));
        }
    }

    #[test]
    fn safe_context_and_message_have_a_fixed_shape() {
        let error = AppError::from(CoreError::NotFound).with_context(ErrorContext::ReadWorld);
        assert_eq!(
            serde_json::to_value(error).unwrap(),
            serde_json::json!({
                "code": "NOT_FOUND", "message": "读取来源不存在", "context": "READ_WORLD"
            })
        );
    }

    #[test]
    fn task_errors_cannot_serialize_as_success() {
        for (error, code) in [
            (TaskError::Cancelled, "TASK_CANCELLED"),
            (TaskError::Outdated, "TASK_OUTDATED"),
            (TaskError::IdExhausted, "TASK_ID_EXHAUSTED"),
        ] {
            let json = serde_json::to_value(AppError::from(error)).unwrap();
            assert_eq!(json["code"], code);
        }
    }

    #[test]
    fn executor_failures_have_stable_public_codes() {
        for (error, code) in [
            (ExecutorError::WorkerUnavailable, "WORKER_FAILED"),
            (ExecutorError::WorkerPanicked, "WORKER_FAILED"),
            (ExecutorError::ResultChannelClosed, "WORKER_FAILED"),
            (ExecutorError::ShuttingDown, "SHUTTING_DOWN"),
        ] {
            let json = serde_json::to_value(AppError::from(error)).unwrap();
            assert_eq!(json["code"], code);
        }
    }
}
