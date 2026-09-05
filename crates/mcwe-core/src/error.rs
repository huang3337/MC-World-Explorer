use std::{fmt, io};

/// 只描述当前只读边界，不包含 UI、IPC 或尚未迁移的格式错误。
#[derive(Debug)]
pub enum CoreError {
    InvalidPath,
    ReparsePoint,
    NotDirectory,
    NotFile,
    NotFound,
    PermissionDenied,
    InUse,
    UnsupportedPlatform,
    Io(io::Error),
}

impl From<io::Error> for CoreError {
    fn from(error: io::Error) -> Self {
        #[cfg(windows)]
        if matches!(error.raw_os_error(), Some(32 | 33)) {
            // ERROR_SHARING_VIOLATION / ERROR_LOCK_VIOLATION。
            return Self::InUse;
        }
        match error.kind() {
            io::ErrorKind::NotFound => Self::NotFound,
            io::ErrorKind::PermissionDenied => Self::PermissionDenied,
            io::ErrorKind::NotADirectory => Self::NotDirectory,
            io::ErrorKind::InvalidInput => Self::InvalidPath,
            _ => Self::Io(error),
        }
    }
}

impl fmt::Display for CoreError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InvalidPath => "路径无效或不属于允许的相对文件路径",
            Self::ReparsePoint => "路径包含不允许跟随的链接或重解析点",
            Self::NotDirectory => "世界根或中间路径不是目录",
            Self::NotFile => "读取目标不是普通文件",
            Self::NotFound => "读取来源不存在",
            Self::PermissionDenied => "没有读取来源的权限",
            Self::InUse => "来源正被不兼容的文件操作占用",
            Self::UnsupportedPlatform => "当前平台尚未实现安全只读边界",
            Self::Io(_) => "读取来源时发生 I/O 错误",
        })
    }
}

impl std::error::Error for CoreError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn io_categories_and_messages_are_stable() {
        assert!(matches!(
            CoreError::from(io::Error::from(io::ErrorKind::NotFound)),
            CoreError::NotFound
        ));
        assert!(matches!(
            CoreError::from(io::Error::from(io::ErrorKind::PermissionDenied)),
            CoreError::PermissionDenied
        ));
        assert_eq!(CoreError::NotFound.to_string(), "读取来源不存在");
        let error = CoreError::from(io::Error::other("private source detail"));
        assert!(std::error::Error::source(&error).is_some());
        assert!(!error.to_string().contains("private"));
    }

    #[cfg(windows)]
    #[test]
    fn sharing_conflicts_are_not_reported_as_missing_files() {
        for code in [32, 33] {
            assert!(matches!(
                CoreError::from(io::Error::from_raw_os_error(code)),
                CoreError::InUse
            ));
        }
    }
}
