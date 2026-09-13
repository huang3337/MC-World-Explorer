#![cfg(windows)]
use mcwe_core::{
    cancel::{Cancellation, NeverCancel},
    world::scanner::scan_selected_path,
    CoreError,
};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
};

struct Fixture {
    root: PathBuf,
    files: Vec<PathBuf>,
    dirs: Vec<PathBuf>,
}
impl Fixture {
    fn new() -> Self {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../target")
            .canonicalize()
            .unwrap()
            .join(format!(
                "scan-test-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
        fs::create_dir(&root).unwrap();
        Self {
            root: root.clone(),
            files: vec![],
            dirs: vec![root],
        }
    }
    fn dir(&mut self, relative: &str) -> PathBuf {
        let mut path = self.root.clone();
        for part in Path::new(relative) {
            path.push(part);
            if !path.exists() {
                fs::create_dir(&path).unwrap();
                self.dirs.push(path.clone());
            }
        }
        path
    }
    fn world(&mut self, relative: &str) -> PathBuf {
        let path = self.dir(relative);
        let bytes = fastnbt::to_bytes(&HashMap::from([(
            "Data",
            fastnbt::Value::Compound(HashMap::from([(
                "LevelName".into(),
                fastnbt::Value::String("同名世界".into()),
            )])),
        )]))
        .unwrap();
        let file = path.join("level.dat");
        fs::write(&file, bytes).unwrap();
        self.files.push(file);
        path
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        for file in self.files.iter().rev() {
            let _ = fs::remove_file(file);
        }
        for dir in self.dirs.iter().rev() {
            let _ = fs::remove_dir(dir);
        }
    }
}

#[test]
fn matches_java_selected_path_layouts_and_keeps_same_named_worlds() {
    let mut f = Fixture::new();
    let world = f.world("game/saves/default");
    f.world("game/versions/实例一/saves/first");
    f.world("game/versions/实例二/saves/second");
    for (path, count) in [
        (world, 1),
        (f.root.join("game/saves"), 1),
        (f.root.join("game"), 3),
        (f.root.join("game/versions"), 2),
        (f.root.join("game/versions/实例一"), 1),
    ] {
        let result = scan_selected_path(&path, &NeverCancel).unwrap();
        assert_eq!(result.worlds.len(), count, "{}", path.display());
        assert_eq!(result.skipped, 0);
    }
    let empty = f.dir("empty");
    assert!(scan_selected_path(&empty, &NeverCancel)
        .unwrap()
        .worlds
        .is_empty());
}

#[test]
fn corrupt_world_is_isolated_and_cancel_is_propagated() {
    let mut f = Fixture::new();
    f.world("saves/good");
    let bad = f.world("saves/bad");
    fs::write(bad.join("level.dat"), b"broken").unwrap();
    let scan = scan_selected_path(&f.root.join("saves"), &NeverCancel).unwrap();
    assert_eq!((scan.worlds.len(), scan.skipped), (1, 1));
    struct Cancel;
    impl Cancellation for Cancel {
        fn is_cancelled(&self) -> bool {
            true
        }
        fn check(&self) -> Result<(), CoreError> {
            Err(CoreError::Cancelled)
        }
    }
    assert!(matches!(
        scan_selected_path(&f.root, &Cancel),
        Err(CoreError::Cancelled)
    ));
}
