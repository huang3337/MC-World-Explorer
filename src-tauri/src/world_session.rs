use crate::{
    error::{AppError, ErrorCode},
    storage::PortablePaths,
};
#[cfg(test)]
use mcwe_core::cancel::NeverCancel;
use mcwe_core::{
    cancel::Cancellation,
    world::{read_world_info, PreviewCenterSource, WorldInfo},
    CoreError, WorldSource,
};
use serde::Serialize;
use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

pub struct WorldSession {
    pub id: u64,
    pub source: WorldSource,
    pub info: WorldInfo,
    display_path: PathBuf,
}
pub struct WorldSessionState {
    inner: Mutex<Inner>,
    portable_root: Option<PathBuf>,
}

pub(crate) struct WorldCandidate {
    source: WorldSource,
    info: WorldInfo,
    display_path: PathBuf,
}
struct Inner {
    last_id: u64,
    current: Option<Arc<WorldSession>>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldSummary {
    pub session_id: u64,
    pub name: String,
    pub display_path: PathBuf,
    pub data_version: Option<i32>,
    pub center_x: i32,
    pub center_y: i32,
    pub center_z: i32,
    pub center_source: &'static str,
    pub world_spawn: Option<PositionSummary>,
}

#[derive(Debug, Serialize)]
pub struct PositionSummary {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl WorldSessionState {
    pub fn new(paths: &Result<PortablePaths, std::io::Error>) -> Self {
        Self {
            inner: Mutex::new(Inner {
                last_id: 0,
                current: None,
            }),
            portable_root: paths.as_ref().ok().map(|p| p.root().to_path_buf()),
        }
    }
    pub(crate) fn portable_root_for_validation(&self) -> Option<PathBuf> {
        self.portable_root.clone()
    }

    pub(crate) fn commit_candidate(
        &self,
        candidate: WorldCandidate,
    ) -> Result<WorldSummary, AppError> {
        let mut inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        if inner.last_id >= crate::protocol::MAX_SAFE_INTEGER_ID {
            return Err(AppError::new(ErrorCode::SessionIdExhausted));
        }
        let id = inner.last_id + 1;
        let session = Arc::new(WorldSession {
            id,
            source: candidate.source,
            info: candidate.info,
            display_path: candidate.display_path,
        });
        let summary = session.summary();
        inner.last_id = id;
        inner.current = Some(session);
        Ok(summary)
    }

    #[cfg(test)]
    pub fn replace_candidate(&self, path: PathBuf) -> Result<WorldSummary, AppError> {
        let candidate = validate_candidate(path, self.portable_root.clone(), &NeverCancel)?;
        self.commit_candidate(candidate)
    }
    pub fn get(&self, id: u64) -> Result<Arc<WorldSession>, AppError> {
        let inner = self.inner.lock().unwrap_or_else(|p| p.into_inner());
        let current = inner
            .current
            .as_ref()
            .ok_or_else(|| AppError::new(ErrorCode::NoActiveWorld))?;
        if current.id != id {
            return Err(AppError::new(ErrorCode::SessionOutdated));
        }
        Ok(Arc::clone(current))
    }
    pub fn is_current(&self, id: u64) -> bool {
        self.inner
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .current
            .as_ref()
            .is_some_and(|s| s.id == id)
    }
    #[cfg(test)]
    pub(crate) fn clear_for_test(&self) {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .current = None;
    }
}

pub(crate) fn validate_candidate(
    path: PathBuf,
    portable_root: Option<PathBuf>,
    cancel: &dyn Cancellation,
) -> Result<WorldCandidate, CoreError> {
    if portable_root
        .as_ref()
        .is_some_and(|root| same_or_parent(&path, root) || same_or_parent(root, &path))
    {
        return Err(CoreError::InvalidPath);
    }
    cancel.check()?;
    let source = WorldSource::new(&path)?;
    let info = read_world_info(&source, cancel)?;
    cancel.check()?;
    Ok(WorldCandidate {
        source,
        info,
        display_path: path,
    })
}
fn same_or_parent(a: &Path, b: &Path) -> bool {
    let a = comparable_windows_path(a);
    let b = comparable_windows_path(b);
    b == a
        || if a.ends_with('\\') {
            b.starts_with(&a)
        } else {
            b.strip_prefix(&a)
                .is_some_and(|rest| rest.starts_with('\\'))
        }
}

fn comparable_windows_path(path: &Path) -> String {
    let path = path.to_string_lossy().replace('/', "\\").to_lowercase();
    if let Some(unc) = path.strip_prefix(r"\\?\unc\") {
        format!(r"\\{unc}")
    } else {
        path.strip_prefix(r"\\?\").unwrap_or(&path).to_owned()
    }
}
impl WorldSession {
    fn summary(&self) -> WorldSummary {
        let center = &self.info.preview_center;
        WorldSummary {
            session_id: self.id,
            name: self.info.name.clone(),
            display_path: self.display_path.clone(),
            data_version: self.info.data_version,
            center_x: center.position.x,
            center_y: center.position.y,
            center_z: center.position.z,
            center_source: match center.source {
                PreviewCenterSource::PlayerRespawn => "playerRespawn",
                PreviewCenterSource::WorldSpawn => "worldSpawn",
                PreviewCenterSource::OriginFallback => "originFallback",
            },
            world_spawn: self
                .info
                .world_spawn
                .as_ref()
                .map(|position| PositionSummary {
                    x: position.x,
                    y: position.y,
                    z: position.z,
                }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overlap_is_segment_aware() {
        assert!(same_or_parent(
            Path::new(r"C:\app"),
            Path::new(r"c:\app\world")
        ));
        assert!(!same_or_parent(
            Path::new(r"C:\app"),
            Path::new(r"C:\apple")
        ));
        assert!(same_or_parent(Path::new(r"C:\"), Path::new(r"C:\world")));
        assert!(same_or_parent(
            Path::new(r"C:\app"),
            Path::new(r"\\?\C:\app\world")
        ));
    }

    #[cfg(windows)]
    mod windows {
        use super::*;
        use crate::storage::PortablePaths;
        use std::{
            fs,
            sync::atomic::{AtomicU64, Ordering},
        };

        struct Fixture {
            root: PathBuf,
            entries: Vec<(PathBuf, bool)>,
        }

        impl Fixture {
            fn new() -> Self {
                static NEXT: AtomicU64 = AtomicU64::new(0);
                let base =
                    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/world-session-tests");
                fs::create_dir_all(&base).unwrap();
                let base = base.canonicalize().unwrap();
                let root = base.join(format!(
                    "case-{}-{}",
                    std::process::id(),
                    NEXT.fetch_add(1, Ordering::Relaxed)
                ));
                fs::create_dir(&root).unwrap();
                Self {
                    entries: vec![(root.clone(), true)],
                    root,
                }
            }

            fn dir(&mut self, relative: &str) -> PathBuf {
                let path = self.root.join(relative);
                fs::create_dir(&path).unwrap();
                self.entries.push((path.clone(), true));
                path
            }

            fn file(&mut self, relative: &str, bytes: &[u8]) -> PathBuf {
                let path = self.root.join(relative);
                fs::write(&path, bytes).unwrap();
                self.entries.push((path.clone(), false));
                path
            }

            fn world(&mut self, name: &str, level_name: &str, spawn_x: i32) -> PathBuf {
                let world = self.dir(name);
                self.dir(&format!("{name}/region"));
                self.file(
                    &format!("{name}/level.dat"),
                    &valid_level(level_name, spawn_x),
                );
                world
            }
        }

        impl Drop for Fixture {
            fn drop(&mut self) {
                for (path, directory) in self.entries.iter().rev() {
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

        fn named(bytes: &mut Vec<u8>, tag: u8, name: &str) {
            bytes.push(tag);
            bytes.extend_from_slice(&(name.len() as u16).to_be_bytes());
            bytes.extend_from_slice(name.as_bytes());
        }

        fn valid_level(level_name: &str, spawn_x: i32) -> Vec<u8> {
            let mut bytes = vec![10, 0, 0];
            named(&mut bytes, 10, "Data");
            named(&mut bytes, 3, "DataVersion");
            bytes.extend_from_slice(&3955_i32.to_be_bytes());
            named(&mut bytes, 8, "LevelName");
            bytes.extend_from_slice(&(level_name.len() as u16).to_be_bytes());
            bytes.extend_from_slice(level_name.as_bytes());
            for (name, value) in [("SpawnX", spawn_x), ("SpawnY", 64), ("SpawnZ", -8)] {
                named(&mut bytes, 3, name);
                bytes.extend_from_slice(&value.to_be_bytes());
            }
            bytes.extend_from_slice(&[0, 0]);
            bytes
        }

        fn state() -> WorldSessionState {
            WorldSessionState::new(&Err(std::io::Error::other("paths unavailable in test")))
        }

        fn error_code(error: AppError) -> serde_json::Value {
            serde_json::to_value(error).unwrap()["code"].clone()
        }

        #[test]
        fn candidate_failure_keeps_old_session_and_success_increments_id() {
            let mut fixture = Fixture::new();
            let first = fixture.world("first", "第一个世界", -17);
            let invalid = fixture.dir("invalid");
            fixture.dir("invalid/region");
            let second = fixture.world("second", "第二个世界", 31);
            let sessions = state();

            let first_summary = sessions.replace_candidate(first).unwrap();
            assert_eq!(first_summary.session_id, 1);
            assert_eq!(first_summary.name, "第一个世界");
            assert!(sessions.replace_candidate(invalid).is_err());
            assert_eq!(sessions.get(1).unwrap().info.name, "第一个世界");

            let second_summary = sessions.replace_candidate(second).unwrap();
            assert_eq!(second_summary.session_id, 2);
            assert_eq!(second_summary.center_x, 31);
            assert_eq!(second_summary.world_spawn.unwrap().x, 31);
            assert_eq!(
                error_code(sessions.get(1).err().unwrap()),
                "SESSION_OUTDATED"
            );
            assert_eq!(sessions.get(2).unwrap().info.name, "第二个世界");
        }

        #[test]
        fn candidate_identity_depends_on_level_dat_not_region_directory() {
            let mut fixture = Fixture::new();
            let candidate = fixture.dir("region-is-file");
            fixture.file("region-is-file/level.dat", &valid_level("错误世界", 0));
            fixture.file("region-is-file/region", b"not a directory");
            let sessions = state();
            let summary = sessions.replace_candidate(candidate).unwrap();
            assert_eq!(summary.name, "错误世界");
            assert_eq!(
                sessions.get(summary.session_id).unwrap().info.name,
                "错误世界"
            );
        }

        #[test]
        fn no_active_foreign_and_exhausted_ids_are_distinct() {
            let mut fixture = Fixture::new();
            let first = fixture.world("first", "第一个世界", 0);
            let second = fixture.world("second", "第二个世界", 16);
            let sessions = state();
            assert_eq!(
                error_code(sessions.get(1).err().unwrap()),
                "NO_ACTIVE_WORLD"
            );
            sessions.replace_candidate(first).unwrap();
            assert_eq!(
                error_code(sessions.get(999).err().unwrap()),
                "SESSION_OUTDATED"
            );
            sessions
                .inner
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .last_id = crate::protocol::MAX_SAFE_INTEGER_ID;
            assert_eq!(
                error_code(sessions.replace_candidate(second).unwrap_err()),
                "SESSION_ID_EXHAUSTED"
            );
            assert_eq!(sessions.get(1).unwrap().info.name, "第一个世界");
        }

        #[test]
        fn portable_overlap_is_rejected_before_world_io() {
            let mut fixture = Fixture::new();
            let portable = fixture.dir("portable");
            let paths = PortablePaths::from_executable(portable.join("MCWE.exe"));
            let sessions = WorldSessionState::new(&paths);
            for candidate in [
                portable.clone(),
                portable.join("nested-world"),
                fixture.root.clone(),
            ] {
                assert_eq!(
                    error_code(sessions.replace_candidate(candidate).unwrap_err()),
                    "INVALID_PATH"
                );
            }
        }

        #[test]
        fn replacing_and_dropping_sessions_release_directory_guards() {
            let mut fixture = Fixture::new();
            let first = fixture.world("first", "第一个世界", 0);
            let second = fixture.world("second", "第二个世界", 16);
            let moved_second = fixture.root.join("moved-second");
            let sessions = state();
            sessions.replace_candidate(first.clone()).unwrap();
            let first_session = sessions.get(1).unwrap();
            assert_eq!(Arc::strong_count(&first_session), 2);
            sessions.replace_candidate(second.clone()).unwrap();
            assert_eq!(Arc::strong_count(&first_session), 1);
            drop(first_session);
            assert!(fs::rename(&second, &moved_second).is_err());
            drop(sessions);
            fs::rename(&second, &moved_second).unwrap();
            fs::rename(&moved_second, &second).unwrap();
        }
    }
}
