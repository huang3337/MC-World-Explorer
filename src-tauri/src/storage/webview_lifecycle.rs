use super::PreparedWebviewData;
use std::sync::Mutex;

pub(crate) struct WebviewDataLifecycle {
    prepared: Mutex<Option<PreparedWebviewData>>,
}

impl WebviewDataLifecycle {
    pub(crate) fn new(prepared: PreparedWebviewData) -> Self {
        Self {
            prepared: Mutex::new(Some(prepared)),
        }
    }

    pub(crate) fn release(&self) -> bool {
        let prepared = match self.prepared.lock() {
            Ok(mut prepared) => prepared.take(),
            Err(poisoned) => poisoned.into_inner().take(),
        };
        let released = prepared.is_some();
        drop(prepared);
        released
    }
}

#[cfg(test)]
mod tests {
    use super::super::{PortablePaths, PreparedWebviewData};
    use super::WebviewDataLifecycle;
    use std::{fs, path::PathBuf, sync::Arc, thread};

    fn fixture(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "mcwe-lifecycle-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    fn lifecycle(root: &PathBuf) -> WebviewDataLifecycle {
        fs::create_dir(root).unwrap();
        let paths = PortablePaths::from_executable(root.join("app.exe")).unwrap();
        WebviewDataLifecycle::new(PreparedWebviewData::prepare(&paths).unwrap())
    }

    #[cfg(windows)]
    #[test]
    fn first_release_unlocks_profile_and_second_is_a_no_op() {
        let root = fixture("release");
        let lifecycle = lifecycle(&root);
        let profile = root.join("config/webview");
        let moved = root.join("profile-moved");
        assert!(fs::rename(&profile, &moved).is_err());
        assert!(lifecycle.release());
        assert!(!lifecycle.release());
        fs::rename(&profile, &moved).unwrap();
        fs::rename(&moved, &profile).unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn concurrent_release_has_exactly_one_winner() {
        let root = fixture("concurrent");
        let lifecycle = Arc::new(lifecycle(&root));
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let lifecycle = Arc::clone(&lifecycle);
                thread::spawn(move || lifecycle.release())
            })
            .collect();
        let released = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .filter(|released| *released)
            .count();
        assert_eq!(released, 1);
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(windows)]
    #[test]
    fn dropping_state_releases_profile_guard() {
        let root = fixture("drop");
        let lifecycle = lifecycle(&root);
        let profile = root.join("config/webview");
        let moved = root.join("profile-moved");
        assert!(fs::rename(&profile, &moved).is_err());
        drop(lifecycle);
        fs::rename(&profile, &moved).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
}
