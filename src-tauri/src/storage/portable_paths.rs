use std::io;
use std::path::{Component, Path, PathBuf};

/// EXE 所在目录派生的便携路径，仅计算名称，不验证存在性或授权写入。
/// 实际目录创建必须另行处理存档重叠、链接及权限边界。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortablePaths {
    root: PathBuf,
}

impl PortablePaths {
    /// 生产入口：只查询当前 EXE 路径，不回退到工作目录或用户目录。
    pub fn from_current_executable() -> io::Result<Self> {
        Self::from_executable(std::env::current_exe()?)
    }

    /// 可注入不存在的模拟 EXE 路径，方便无文件系统副作用的测试。
    pub fn from_executable(executable: impl AsRef<Path>) -> io::Result<Self> {
        let executable = executable.as_ref();
        let invalid = || {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                "需要不含父目录跳转的绝对 EXE 文件路径",
            )
        };
        if !executable.is_absolute()
            || executable.file_name().is_none()
            || executable
                .components()
                .any(|part| matches!(part, Component::ParentDir | Component::CurDir))
        {
            return Err(invalid());
        }
        #[cfg(windows)]
        {
            use std::path::Prefix;
            match executable.components().next() {
                Some(Component::Prefix(prefix))
                    if matches!(
                        prefix.kind(),
                        Prefix::Disk(_)
                            | Prefix::VerbatimDisk(_)
                            | Prefix::UNC(_, _)
                            | Prefix::VerbatimUNC(_, _)
                    ) => {}
                _ => return Err(invalid()),
            }
        }
        let root = executable.parent().ok_or_else(invalid)?.to_path_buf();
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn cache(&self) -> PathBuf {
        self.root.join("cache")
    }

    pub fn logs(&self) -> PathBuf {
        self.root.join("logs")
    }

    pub fn exports(&self) -> PathBuf {
        self.root.join("exports")
    }

    pub fn config(&self) -> PathBuf {
        self.root.join("config")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::{Path, PathBuf};

    fn simulated_exe() -> PathBuf {
        std::env::temp_dir()
            .join(format!(
                "mcwe-paths-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ))
            .join("便携 程序")
            .join("MC-World-Explorer.exe")
    }

    #[test]
    fn derives_all_directories_from_injected_executable() {
        let exe = simulated_exe();
        let expected_root = exe.parent().unwrap();
        let paths = PortablePaths::from_executable(&exe).unwrap();
        assert_eq!(paths.root(), expected_root);
        assert_eq!(paths.cache(), expected_root.join("cache"));
        assert_eq!(paths.logs(), expected_root.join("logs"));
        assert_eq!(paths.exports(), expected_root.join("exports"));
        assert_eq!(paths.config(), expected_root.join("config"));
    }

    #[test]
    fn querying_paths_does_not_create_anything() {
        let exe = simulated_exe();
        let sentinel = exe.parent().unwrap().parent().unwrap();
        assert!(!sentinel.exists());
        let paths = PortablePaths::from_executable(&exe).unwrap();
        for path in [paths.cache(), paths.logs(), paths.exports(), paths.config()] {
            assert!(!path.exists());
        }
        assert!(!paths.root().exists());
        assert!(!exe.exists());
        assert!(!sentinel.exists());
    }

    #[test]
    fn independent_executable_locations_do_not_share_a_root() {
        let exe = simulated_exe();
        let other = exe.parent().unwrap().join("other").join("renamed.exe");
        let first = PortablePaths::from_executable(exe).unwrap();
        let second = PortablePaths::from_executable(&other).unwrap();
        assert_eq!(second.root(), other.parent().unwrap());
        assert_ne!(first.cache(), second.cache());
    }

    #[test]
    fn rejects_empty_relative_and_parent_traversal_inputs() {
        for input in [
            Path::new(""),
            Path::new("app.exe"),
            Path::new("relative/app.exe"),
        ] {
            assert_eq!(
                PortablePaths::from_executable(input).unwrap_err().kind(),
                std::io::ErrorKind::InvalidInput
            );
        }
        let mut raw = simulated_exe().parent().unwrap().as_os_str().to_os_string();
        raw.push(std::path::MAIN_SEPARATOR.to_string());
        raw.push("..");
        raw.push(std::path::MAIN_SEPARATOR.to_string());
        raw.push("app.exe");
        assert_eq!(
            PortablePaths::from_executable(PathBuf::from(raw))
                .unwrap_err()
                .kind(),
            std::io::ErrorKind::InvalidInput
        );
    }

    #[cfg(windows)]
    #[test]
    fn handles_drive_root_and_verbatim_paths_without_io() {
        let paths = PortablePaths::from_executable(r"C:\MC-World-Explorer.exe").unwrap();
        assert_eq!(paths.cache(), Path::new(r"C:\cache"));
        let paths = PortablePaths::from_executable(r"\\?\C:\便携\app.exe").unwrap();
        assert_eq!(paths.config(), Path::new(r"\\?\C:\便携\config"));
        for invalid in [r"C:\", r"C:app.exe", r"\app.exe", r"\\.\pipe\app.exe"] {
            assert_eq!(
                PortablePaths::from_executable(invalid).unwrap_err().kind(),
                std::io::ErrorKind::InvalidInput
            );
        }
    }
}
