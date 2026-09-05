use super::PortablePaths;
use std::{
    fs, io,
    path::{Path, PathBuf},
};

const OVERRIDE_ENVIRONMENT: [&str; 5] = [
    "WEBVIEW2_BROWSER_EXECUTABLE_FOLDER",
    "WEBVIEW2_USER_DATA_FOLDER",
    "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
    "WEBVIEW2_CHANNEL_SEARCH_KIND",
    "WEBVIEW2_RELEASE_CHANNELS",
];

#[derive(Debug)]
pub(crate) struct PreparedWebviewData {
    path: PathBuf,
    #[cfg(windows)]
    _guards: Vec<fs::File>,
}

impl PreparedWebviewData {
    pub(crate) fn prepare(paths: &PortablePaths) -> io::Result<Self> {
        reject_world_ancestor(paths.root())?;
        verify_existing_ancestors(paths.root())?;
        let config = paths.config();
        create_checked_directory(&config)?;
        #[cfg(windows)]
        let mut _guards = vec![
            open_directory_guard(paths.root())?,
            open_directory_guard(&config)?,
        ];
        let path = config.join("webview");
        create_checked_directory(&path)?;
        #[cfg(windows)]
        _guards.push(open_directory_guard(&path)?);
        verify_tree_has_no_reparse_points(&path)?;

        Ok(Self {
            path,
            #[cfg(windows)]
            _guards,
        })
    }

    pub(crate) fn path(&self) -> &Path {
        &self.path
    }
}

fn reject_world_ancestor(root: &Path) -> io::Result<()> {
    for ancestor in root.ancestors() {
        for marker in ["level.dat", "level.dat_old"] {
            match fs::symlink_metadata(ancestor.join(marker)) {
                Ok(_) => {
                    return Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "world overlap",
                    ))
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
        }
    }
    Ok(())
}

fn verify_existing_ancestors(path: &Path) -> io::Result<()> {
    for ancestor in path.ancestors().collect::<Vec<_>>().into_iter().rev() {
        if ancestor.exists() {
            verify_directory(ancestor)?;
        }
    }
    Ok(())
}

fn create_checked_directory(path: &Path) -> io::Result<()> {
    match fs::create_dir(path) {
        Ok(()) => verify_directory(path),
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => verify_directory(path),
        Err(error) => Err(error),
    }
}

fn verify_directory(path: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "reparse point"));
    }
    if !metadata.is_dir() {
        return Err(io::Error::new(
            io::ErrorKind::NotADirectory,
            "not a directory",
        ));
    }
    Ok(())
}

fn verify_tree_has_no_reparse_points(root: &Path) -> io::Result<()> {
    verify_directory(root)?;
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "profile reparse point",
            ));
        }
        if metadata.is_dir() {
            verify_tree_has_no_reparse_points(&entry.path())?;
        }
    }
    Ok(())
}

#[cfg(windows)]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    metadata.file_attributes() & 0x400 != 0
}

#[cfg(not(windows))]
fn is_reparse_point(_: &fs::Metadata) -> bool {
    false
}

#[cfg(windows)]
fn open_directory_guard(path: &Path) -> io::Result<fs::File> {
    use std::os::windows::fs::OpenOptionsExt;
    let file = fs::OpenOptions::new()
        .read(true)
        .share_mode(0x1 | 0x2) // FILE_SHARE_READ | FILE_SHARE_WRITE，刻意不允许删除/改名。
        .custom_flags(0x0200_0000 | 0x0020_0000) // BACKUP_SEMANTICS | OPEN_REPARSE_POINT
        .open(path)?;
    let metadata = file.metadata()?;
    if !metadata.is_dir() || is_reparse_point(&metadata) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "guard target is not a plain directory",
        ));
    }
    Ok(file)
}

pub(crate) fn detect_webview_overrides(exe_name: &str, app_id: &str) -> io::Result<()> {
    let snapshot = OverrideSnapshot {
        environment: OVERRIDE_ENVIRONMENT
            .iter()
            .filter(|name| std::env::var_os(name).is_some())
            .map(|name| (*name).to_owned())
            .collect(),
        registry: registry_overrides(exe_name, app_id)?,
    };
    snapshot.validate()
}

#[derive(Debug, Default)]
struct OverrideSnapshot {
    environment: Vec<String>,
    registry: Vec<String>,
}

impl OverrideSnapshot {
    fn validate(&self) -> io::Result<()> {
        if self.environment.is_empty() && self.registry.is_empty() {
            return Ok(());
        }
        Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "WebView2 override",
        ))
    }
}

#[cfg(windows)]
fn registry_overrides(exe_name: &str, app_id: &str) -> io::Result<Vec<String>> {
    use winreg::{
        enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, KEY_READ},
        RegKey,
    };
    const NAMES: [&str; 5] = [
        "BrowserExecutableFolder",
        "UserDataFolder",
        "AdditionalBrowserArguments",
        "ChannelSearchKind",
        "ReleaseChannels",
    ];
    let mut found = Vec::new();
    for (root_name, root) in [
        ("HKLM", RegKey::predef(HKEY_LOCAL_MACHINE)),
        ("HKCU", RegKey::predef(HKEY_CURRENT_USER)),
    ] {
        for name in NAMES {
            let key_path = format!(r"Software\Policies\Microsoft\Edge\WebView2\{name}");
            match root.open_subkey_with_flags(key_path, KEY_READ) {
                Ok(key) => {
                    for candidate in [app_id, exe_name, "*"] {
                        match key.get_raw_value(candidate) {
                            Ok(_) => found.push(format!("{root_name}/{name}/{candidate}")),
                            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                            Err(error) => return Err(error),
                        }
                    }
                }
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
        }
    }
    Ok(found)
}

#[cfg(not(windows))]
fn registry_overrides(_: &str, _: &str) -> io::Result<Vec<String>> {
    Ok(Vec::new())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "mcwe-webview-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    #[test]
    fn creates_only_config_and_webview_and_can_reuse_them() {
        let root = fixture("create");
        fs::create_dir(&root).unwrap();
        let paths = PortablePaths::from_executable(root.join("renamed.exe")).unwrap();
        let first = PreparedWebviewData::prepare(&paths).unwrap();
        assert_eq!(first.path(), root.join("config/webview"));
        assert!(first.path().is_dir());
        drop(first);
        PreparedWebviewData::prepare(&paths).unwrap();
        assert!(!paths.cache().exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_file_placeholder_and_world_ancestor() {
        let root = fixture("reject");
        fs::create_dir(&root).unwrap();
        fs::write(root.join("config"), b"sentinel").unwrap();
        let paths = PortablePaths::from_executable(root.join("app.exe")).unwrap();
        assert!(PreparedWebviewData::prepare(&paths).is_err());
        fs::remove_file(root.join("config")).unwrap();
        fs::write(root.join("level.dat"), b"world").unwrap();
        assert_eq!(
            PreparedWebviewData::prepare(&paths).unwrap_err().kind(),
            io::ErrorKind::PermissionDenied
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn injected_override_snapshots_are_rejected_without_touching_process_state() {
        assert!(OverrideSnapshot::default().validate().is_ok());
        for snapshot in [
            OverrideSnapshot {
                environment: vec!["WEBVIEW2_USER_DATA_FOLDER".into()],
                registry: vec![],
            },
            OverrideSnapshot {
                environment: vec![],
                registry: vec!["HKLM/UserDataFolder/app.exe".into()],
            },
        ] {
            assert_eq!(
                snapshot.validate().unwrap_err().kind(),
                io::ErrorKind::PermissionDenied
            );
        }
    }

    #[cfg(windows)]
    #[test]
    fn guards_prevent_profile_root_replacement_until_drop() {
        let root = fixture("guard");
        fs::create_dir(&root).unwrap();
        let paths = PortablePaths::from_executable(root.join("app.exe")).unwrap();
        let prepared = PreparedWebviewData::prepare(&paths).unwrap();
        let moved = root.join("webview-moved");
        assert!(fs::rename(prepared.path(), &moved).is_err());
        drop(prepared);
        fs::rename(root.join("config/webview"), &moved).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(windows)]
    #[test]
    #[ignore = "requires Windows directory symlink privilege; run with --include-ignored"]
    fn rejects_directory_symlink_without_writing_through_it() {
        use std::os::windows::fs::symlink_dir;
        let root = fixture("link");
        let outside = fixture("outside");
        fs::create_dir(&root).unwrap();
        fs::create_dir(&outside).unwrap();
        if let Err(error) = symlink_dir(&outside, root.join("config")) {
            fs::remove_dir_all(&root).unwrap();
            fs::remove_dir_all(&outside).unwrap();
            panic!("此专项测试需要 Windows 目录符号链接创建权限：{error}");
        }
        let paths = PortablePaths::from_executable(root.join("app.exe")).unwrap();
        assert!(PreparedWebviewData::prepare(&paths).is_err());
        assert!(fs::read_dir(&outside).unwrap().next().is_none());
        fs::remove_dir_all(root).unwrap();
        fs::remove_dir_all(outside).unwrap();
    }
}
