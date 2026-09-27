use crate::CoreError;
use std::ffi::OsString;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SourceFileState {
    Missing,
    File { len: u64, modified: SystemTime },
}

/// 经过文件系统校验的世界根路径，不代表已解析的 Minecraft 世界。
/// 生命周期内保留世界根及祖先目录的只读保护句柄，防止会话中途被替换。
#[derive(Debug)]
pub struct WorldSource {
    root: PathBuf,
    #[cfg(windows)]
    _root_guards: Vec<File>,
}

impl WorldSource {
    pub fn new(root: impl AsRef<Path>) -> Result<Self, CoreError> {
        #[cfg(windows)]
        {
            let root = windows::absolute_root(root.as_ref())?;
            let root_guards = windows::guard_directories(&root)?;
            Ok(Self {
                root,
                _root_guards: root_guards,
            })
        }
        #[cfg(not(windows))]
        {
            let _ = root;
            Err(CoreError::UnsupportedPlatform)
        }
    }

    /// 只接受普通相对路径。拒绝所有重解析点，包括仍指向根内的链接。
    /// 已有不兼容句柄时返回 InUse，不放宽共享模式或绕过检查。
    pub fn open_file(&self, relative: impl AsRef<Path>) -> Result<WorldFile, CoreError> {
        #[cfg(windows)]
        {
            let relative = windows::relative_file(relative.as_ref())?;
            let target = self.root.join(relative);
            let parent = target.parent().ok_or(CoreError::InvalidPath)?;
            // guard 包含路径锚点及全部祖先，不只检查世界根以下的目录。
            let _guards = windows::guard_directories(parent)?;
            let file = windows::open_checked(&target, false)?;
            Ok(WorldFile { file })
        }
        #[cfg(not(windows))]
        {
            let _ = (&self.root, relative);
            Err(CoreError::UnsupportedPlatform)
        }
    }

    /// 返回安全相对文件的只读来源状态，用于缓存失效；不暴露实际路径。
    pub fn source_file_state(
        &self,
        relative: impl AsRef<Path>,
    ) -> Result<SourceFileState, CoreError> {
        let file = match self.open_file(relative) {
            Ok(file) => file,
            Err(CoreError::NotFound) => return Ok(SourceFileState::Missing),
            Err(error) => return Err(error),
        };
        let metadata = file.file.metadata()?;
        Ok(SourceFileState::File {
            len: metadata.len(),
            modified: metadata.modified()?,
        })
    }

    /// 验证根目录下的普通相对目录存在且整条路径不含重解析点。
    /// 只在调用期间持有额外目录句柄，不向调用方暴露文件系统能力。
    pub fn validate_directory(&self, relative: impl AsRef<Path>) -> Result<(), CoreError> {
        #[cfg(windows)]
        {
            let relative = windows::relative_file(relative.as_ref())?;
            let target = self.root.join(relative);
            drop(windows::guard_directories(&target)?);
            Ok(())
        }
        #[cfg(not(windows))]
        {
            let _ = (&self.root, relative);
            Err(CoreError::UnsupportedPlatform)
        }
    }

    /// 枚举安全相对目录中的普通文件名。结果不包含路径，且受调用方给出的数量上限约束。
    pub fn list_regular_file_names(
        &self,
        relative: impl AsRef<Path>,
        max_entries: usize,
    ) -> Result<Vec<OsString>, CoreError> {
        if max_entries == 0 {
            return Err(CoreError::ResourceLimit);
        }
        #[cfg(windows)]
        {
            let relative = windows::relative_file(relative.as_ref())?;
            let target = self.root.join(relative);
            let _guards = windows::guard_directories(&target)?;
            let mut names = Vec::new();
            for entry in std::fs::read_dir(target)? {
                let entry = entry?;
                if names.len() >= max_entries {
                    return Err(CoreError::ResourceLimit);
                }
                let name = entry.file_name();
                windows::validate_name(&name)?;
                if entry.file_type()?.is_file() {
                    names.push(name);
                }
            }
            names.sort();
            Ok(names)
        }
        #[cfg(not(windows))]
        {
            let _ = (&self.root, relative, max_entries);
            Err(CoreError::UnsupportedPlatform)
        }
    }
}

/// 不暴露 File、句柄或元数据修改方法；只支持 Read 和 Seek。
/// 文件关闭前仅允许其他读共享，不承诺运行中世界的跨文件一致性。
///
/// ```compile_fail
/// use mcwe_core::WorldFile;
/// fn requires_write<T: std::io::Write>() {}
/// requires_write::<WorldFile>();
/// ```
///
/// ```compile_fail
/// use mcwe_core::WorldFile;
/// fn truncate(file: WorldFile) { file.set_len(0).unwrap(); }
/// ```
#[derive(Debug)]
pub struct WorldFile {
    file: File,
}

impl Read for WorldFile {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.file.read(buffer)
    }
}

impl Seek for WorldFile {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        self.file.seek(position)
    }
}

#[cfg(windows)]
mod windows {
    use super::*;
    use std::ffi::OsStr;
    use std::fs::OpenOptions;
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::fs::{MetadataExt, OpenOptionsExt};
    use std::path::{Component, Prefix};

    const FILE_SHARE_READ: u32 = 0x0000_0001;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
    const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;

    pub(super) fn validate_name(name: &OsStr) -> Result<(), CoreError> {
        let units: Vec<_> = name.encode_wide().collect();
        if units.is_empty()
            || units
                .iter()
                .any(|c| *c < 32 || [34, 42, 47, 58, 60, 62, 63, 92, 124].contains(c))
            || matches!(units.last(), Some(32 | 46))
        {
            return Err(CoreError::InvalidPath);
        }
        // 防止 DOS 设备别名，包括带扩展名与 Windows 识别的上标数字别名。
        let name = name.to_string_lossy().to_uppercase();
        let stem = name.split('.').next().unwrap_or_default();
        if ["CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"].contains(&stem)
            || ["COM", "LPT"].iter().any(|prefix| {
                stem.strip_prefix(prefix).is_some_and(|suffix| {
                    ["1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³"].contains(&suffix)
                })
            })
        {
            return Err(CoreError::InvalidPath);
        }
        Ok(())
    }

    pub(super) fn relative_file(path: &Path) -> Result<PathBuf, CoreError> {
        let mut result = PathBuf::new();
        for part in path.components() {
            match part {
                Component::Normal(name) => {
                    validate_name(name)?;
                    result.push(name);
                }
                _ => return Err(CoreError::InvalidPath),
            }
        }
        if result.as_os_str().is_empty() {
            return Err(CoreError::InvalidPath);
        }
        Ok(result)
    }

    pub(super) fn absolute_root(path: &Path) -> Result<PathBuf, CoreError> {
        let mut parts = path.components();
        let Some(Component::Prefix(prefix)) = parts.next() else {
            return Err(CoreError::InvalidPath);
        };
        let mut result = match prefix.kind() {
            Prefix::Disk(drive) | Prefix::VerbatimDisk(drive) => {
                PathBuf::from(format!("\\\\?\\{}:\\", char::from(drive)))
            }
            Prefix::UNC(server, share) | Prefix::VerbatimUNC(server, share) => {
                validate_name(server)?;
                validate_name(share)?;
                let mut root = PathBuf::from(r"\\?\UNC");
                root.push(server);
                root.push(share);
                root.push("");
                root
            }
            _ => return Err(CoreError::InvalidPath),
        };
        if parts.next() != Some(Component::RootDir) {
            return Err(CoreError::InvalidPath);
        }
        for part in parts {
            match part {
                Component::Normal(name) => {
                    validate_name(name)?;
                    result.push(name);
                }
                _ => return Err(CoreError::InvalidPath),
            }
        }
        Ok(result)
    }

    pub(super) fn open_checked(path: &Path, directory: bool) -> Result<File, CoreError> {
        let mut options = OpenOptions::new();
        options
            .read(true)
            .share_mode(FILE_SHARE_READ)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS);
        // 目录也必须请求实际读取权限，只有 FILE_READ_ATTRIBUTES 的句柄
        // 不参与所需的读/写/删除共享约束。这里不请求写权限或提升权限。
        let file = options.open(path)?;
        let metadata = file.metadata()?;
        if metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            return Err(CoreError::ReparsePoint);
        }
        if directory && !metadata.is_dir() {
            return Err(CoreError::NotDirectory);
        }
        if !directory && !metadata.is_file() {
            return Err(CoreError::NotFile);
        }
        Ok(file)
    }

    pub(super) fn guard_directories(path: &Path) -> Result<Vec<File>, CoreError> {
        let path = absolute_root(path)?;
        let mut current = PathBuf::new();
        let mut guards = Vec::new();
        for part in path.components() {
            current.push(part.as_os_str());
            // 只在完整锚点（C:\ 或 UNC share）形成后打开，不能打开驱动器设备。
            if matches!(part, Component::RootDir | Component::Normal(_)) {
                guards.push(open_checked(&current, true)?);
            }
        }
        Ok(guards)
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use crate::CoreError;
    use std::collections::hash_map::DefaultHasher;
    use std::fs;
    use std::hash::{Hash, Hasher};
    use std::io::{Read, Seek, SeekFrom};
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};

    // 只在已忽略的 target 中创建合成数据；按已创建清单逆序删除，不递归清理。
    struct Fixture {
        root: PathBuf,
        entries: Vec<(PathBuf, bool)>,
    }

    impl Fixture {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let base = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target/mcwe-core-tests");
            fs::create_dir_all(&base).unwrap();
            let base = base.canonicalize().unwrap();
            let root = loop {
                let root = base.join(format!(
                    "case-{}-{}",
                    std::process::id(),
                    NEXT.fetch_add(1, Ordering::Relaxed)
                ));
                match fs::create_dir(&root) {
                    Ok(()) => break root,
                    Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                    Err(e) => panic!("fixture: {e}"),
                }
            };
            Self {
                entries: vec![(root.clone(), true)],
                root,
            }
        }

        fn dir(&mut self, name: &str) -> PathBuf {
            let path = self.root.join(name);
            fs::create_dir(&path).unwrap();
            self.entries.push((path.clone(), true));
            path
        }

        fn file(&mut self, name: &str, bytes: &[u8]) -> PathBuf {
            let path = self.root.join(name);
            fs::write(&path, bytes).unwrap();
            self.entries.push((path.clone(), false));
            path
        }

        fn link(&mut self, name: &str, target: &Path, directory: bool) -> PathBuf {
            let path = self.root.join(name);
            if directory {
                std::os::windows::fs::symlink_dir(target, &path)
                    .expect("symlink test requires Windows symlink permission");
            } else {
                std::os::windows::fs::symlink_file(target, &path)
                    .expect("symlink test requires Windows symlink permission");
            }
            self.entries.push((path.clone(), directory));
            path
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            for (path, directory) in self.entries.iter().rev() {
                assert!(path.starts_with(&self.root));
                let result = if *directory {
                    fs::remove_dir(path)
                } else {
                    fs::remove_file(path)
                };
                if let Err(error) = result {
                    if error.kind() != std::io::ErrorKind::NotFound {
                        eprintln!("fixture cleanup failed: {}: {error}", path.display());
                    }
                }
            }
        }
    }

    fn content_hash(bytes: &[u8]) -> u64 {
        let mut hasher = DefaultHasher::new();
        bytes.hash(&mut hasher);
        hasher.finish()
    }

    #[test]
    fn reads_and_seeks_without_changing_source() {
        let mut fixture = Fixture::new();
        fixture.dir("世界");
        fixture.dir("世界/region");
        let path = fixture.file("世界/region/r.0.0.mca", b"synthetic region bytes");
        let before = fs::metadata(&path).unwrap();
        let original = fs::read(&path).unwrap();
        let world = WorldSource::new(fixture.root.join("世界")).unwrap();
        let ordinary_root = fixture
            .root
            .to_str()
            .unwrap()
            .strip_prefix(r"\\?\")
            .unwrap();
        WorldSource::new(ordinary_root).unwrap();
        let mut reader = world.open_file("region/r.0.0.mca").unwrap();
        let mut actual = Vec::new();
        reader.read_to_end(&mut actual).unwrap();
        assert_eq!(actual, original);
        reader.seek(SeekFrom::Start(0)).unwrap();
        let mut prefix = [0; 9];
        reader.read_exact(&mut prefix).unwrap();
        assert_eq!(&prefix, b"synthetic");
        drop(reader);
        let after = fs::metadata(&path).unwrap();
        assert_eq!(before.len(), after.len());
        assert_eq!(before.modified().unwrap(), after.modified().unwrap());
        assert_eq!(
            content_hash(&original),
            content_hash(&fs::read(&path).unwrap())
        );
    }

    #[test]
    fn rejects_traversal_absolute_device_and_ambiguous_names() {
        let fixture = Fixture::new();
        let world = WorldSource::new(&fixture.root).unwrap();
        for path in [
            "",
            ".",
            "..",
            "../outside",
            "region/../../outside",
            "C:\\outside",
            "C:outside",
            "\\outside",
            "\\\\server\\share\\file",
            "\\\\?\\C:\\outside",
            "\\\\.\\NUL",
            "level.dat:stream",
            "region./file",
            "file ",
            "NUL",
            "con.txt",
            "COM1",
            "LPT9.dat",
            "bad\0name",
        ] {
            assert!(
                matches!(world.open_file(path), Err(CoreError::InvalidPath)),
                "accepted {path:?}"
            );
        }
        assert!(matches!(
            world.open_file(&fixture.root),
            Err(CoreError::InvalidPath)
        ));
    }

    #[test]
    fn root_must_exist_and_be_an_absolute_directory() {
        let mut fixture = Fixture::new();
        let file = fixture.file("file", b"data");
        assert!(matches!(
            WorldSource::new("relative"),
            Err(CoreError::InvalidPath)
        ));
        // verbatim PathBuf::join 会在进入 API 前折叠 ..，用原始字符串保留输入。
        let mut parent_input = fixture.root.as_os_str().to_os_string();
        parent_input.push(r"\..");
        assert!(matches!(
            WorldSource::new(PathBuf::from(parent_input)),
            Err(CoreError::InvalidPath)
        ));
        assert!(matches!(
            WorldSource::new(fixture.root.join("missing")),
            Err(CoreError::NotFound)
        ));
        assert!(matches!(
            WorldSource::new(file),
            Err(CoreError::NotDirectory)
        ));
    }

    #[test]
    fn missing_file_and_directory_have_stable_errors() {
        let mut fixture = Fixture::new();
        fixture.dir("region");
        let world = WorldSource::new(&fixture.root).unwrap();
        assert!(matches!(
            world.open_file("missing"),
            Err(CoreError::NotFound)
        ));
        assert!(!fixture.root.join("missing").exists());
        assert!(matches!(world.open_file("region"), Err(CoreError::NotFile)));
        fixture.file("file", b"data");
        assert!(matches!(
            world.open_file("file/child"),
            Err(CoreError::NotDirectory)
        ));
    }

    #[test]
    fn validates_only_safe_relative_directories() {
        let mut fixture = Fixture::new();
        fixture.dir("region");
        fixture.file("ordinary-file", b"data");
        let world = WorldSource::new(&fixture.root).unwrap();
        world.validate_directory("region").unwrap();
        assert!(matches!(
            world.validate_directory("missing"),
            Err(CoreError::NotFound)
        ));
        assert!(matches!(
            world.validate_directory("ordinary-file"),
            Err(CoreError::NotDirectory)
        ));
        assert!(matches!(
            world.validate_directory("../outside"),
            Err(CoreError::InvalidPath)
        ));
    }

    #[test]
    #[ignore = "requires Windows symlink privilege; run the test binary elevated with --include-ignored"]
    fn rejects_directory_file_and_dangling_symlinks() {
        let mut fixture = Fixture::new();
        let world_root = fixture.dir("world");
        let outside = fixture.dir("outside");
        let secret = fixture.file("outside/secret", b"outside");
        fixture.link("world/escape", &outside, true);
        fixture.link("world/file-link", &secret, false);
        fixture.link("world/dangling", &outside.join("missing"), false);
        let internal = fixture.file("world/internal", b"inside");
        fixture.link("world/internal-link", &internal, false);
        let world = WorldSource::new(&world_root).unwrap();
        for path in ["escape/secret", "file-link", "dangling", "internal-link"] {
            assert!(
                matches!(world.open_file(path), Err(CoreError::ReparsePoint)),
                "accepted {path}"
            );
        }
        let linked_root = fixture.link("linked-root", &world_root, true);
        assert!(matches!(
            WorldSource::new(linked_root),
            Err(CoreError::ReparsePoint)
        ));
    }

    #[test]
    fn rejects_junction_escape() {
        let mut fixture = Fixture::new();
        let world_root = fixture.dir("world & '测试'");
        let outside = fixture.dir("outside");
        fixture.file("outside/secret", b"outside");
        let junction = world_root.join("junction & '测试'");
        let output = std::process::Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", "New-Item -ItemType Junction -Path $env:MCWE_TEST_LINK -Target $env:MCWE_TEST_TARGET -ErrorAction Stop | Out-Null"])
            .env("MCWE_TEST_LINK", &junction)
            .env("MCWE_TEST_TARGET", &outside)
            .output().unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        fixture.entries.push((junction, true));
        let world = WorldSource::new(world_root).unwrap();
        assert!(matches!(
            world.validate_directory("junction & '测试'"),
            Err(CoreError::ReparsePoint)
        ));
        assert!(matches!(
            world.open_file("junction & '测试'/secret"),
            Err(CoreError::ReparsePoint)
        ));
    }

    #[test]
    fn directory_guards_prevent_replacement_and_release_on_drop() {
        let mut fixture = Fixture::new();
        let parent = fixture.dir("parent");
        let child = fixture.dir("parent/child");
        let guards = windows::guard_directories(&child).unwrap();
        let moved = fixture.root.join("moved");
        std::thread::scope(|scope| {
            scope
                .spawn(|| {
                    assert!(fs::rename(&parent, &moved).is_err());
                    assert!(fs::remove_dir(&child).is_err());
                })
                .join()
                .unwrap();
        });
        drop(guards);
        fs::rename(&parent, &moved).unwrap();
        fs::rename(&moved, &parent).unwrap();
    }

    #[test]
    fn world_source_retains_root_guards_until_session_drop() {
        let mut fixture = Fixture::new();
        let world_root = fixture.dir("world");
        let path = fixture.file("world/file", b"data");
        let world = WorldSource::new(&world_root).unwrap();
        assert!(matches!(
            world.open_file("missing"),
            Err(CoreError::NotFound)
        ));
        let reader = world.open_file("file").unwrap();
        assert!(fs::OpenOptions::new().write(true).open(&path).is_err());
        drop(reader);
        let moved = fixture.root.join("moved");
        assert!(fs::rename(&world_root, &moved).is_err());
        drop(world);
        fs::rename(&world_root, &moved).unwrap();
        fs::rename(&moved, &world_root).unwrap();
        fs::OpenOptions::new().write(true).open(path).unwrap();
    }

    #[test]
    fn existing_write_handle_causes_in_use_without_fallback() {
        use std::os::windows::fs::OpenOptionsExt;
        let mut fixture = Fixture::new();
        let path = fixture.file("file", b"data");
        let world = WorldSource::new(&fixture.root).unwrap();
        let writer = fs::OpenOptions::new()
            .write(true)
            .share_mode(7)
            .open(&path)
            .unwrap();
        assert!(matches!(world.open_file("file"), Err(CoreError::InUse)));
        drop(writer);
        world.open_file("file").unwrap();
    }

    #[test]
    #[ignore = "requires Windows symlink privilege; run the test binary elevated with --include-ignored"]
    fn a_root_replaced_by_a_link_is_rejected_on_next_open() {
        let mut fixture = Fixture::new();
        let world_root = fixture.dir("world");
        let outside = fixture.dir("outside");
        fixture.file("outside/secret", b"outside");
        let world = WorldSource::new(&world_root).unwrap();
        fs::remove_dir(&world_root).unwrap();
        fixture.link("world", &outside, true);
        assert!(matches!(
            world.open_file("secret"),
            Err(CoreError::ReparsePoint)
        ));
    }
}
