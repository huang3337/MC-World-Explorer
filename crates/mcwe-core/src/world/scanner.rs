//! Java WorldScanner::scanSelectedPath 的有界目录布局识别。
use super::read_world_info;
use crate::{cancel::Cancellation, CoreError, WorldSource};
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub struct DiscoveredWorld {
    pub path: PathBuf,
    pub group: String,
    pub name: String,
}

#[derive(Debug, Default)]
pub struct WorldScan {
    pub worlds: Vec<DiscoveredWorld>,
    pub skipped: usize,
}

// Limits bound enumeration and response size, not Minecraft world contents.
const MAX_ENTRIES: usize = 100_000;
const MAX_WORLDS: usize = 4096;

fn children(
    path: &Path,
    cancel: &dyn Cancellation,
    budget: &mut usize,
) -> Result<Vec<PathBuf>, CoreError> {
    let _guard = WorldSource::new(path)?;
    let mut dirs = Vec::new();
    for entry in std::fs::read_dir(path)? {
        cancel.check()?;
        *budget = budget.checked_sub(1).ok_or(CoreError::ResourceLimit)?;
        let Ok(entry) = entry else { continue };
        if entry.file_type().is_ok_and(|kind| kind.is_dir())
            && WorldSource::new(entry.path()).is_ok()
        {
            dirs.push(entry.path());
        }
    }
    dirs.sort();
    Ok(dirs)
}

fn has_level(path: &Path) -> bool {
    WorldSource::new(path)
        .and_then(|s| s.open_file("level.dat"))
        .is_ok()
}

fn label(path: &Path) -> String {
    path.file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned()
}

fn add_world(
    path: PathBuf,
    group: &str,
    scan: &mut WorldScan,
    cancel: &dyn Cancellation,
) -> Result<(), CoreError> {
    cancel.check()?;
    match WorldSource::new(&path).and_then(|source| read_world_info(&source, cancel)) {
        Ok(info) => {
            if scan.worlds.len() >= MAX_WORLDS {
                return Err(CoreError::ResourceLimit);
            }
            scan.worlds.push(DiscoveredWorld {
                name: if info.name.is_empty() {
                    label(&path)
                } else {
                    info.name
                },
                path,
                group: group.into(),
            });
        }
        Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
        Err(_) => scan.skipped += 1,
    }
    Ok(())
}

fn saves(
    path: &Path,
    group: &str,
    scan: &mut WorldScan,
    cancel: &dyn Cancellation,
    budget: &mut usize,
) -> Result<(), CoreError> {
    let dirs = match children(path, cancel, budget) {
        Ok(dirs) => dirs,
        Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
        Err(CoreError::ResourceLimit) => return Err(CoreError::ResourceLimit),
        Err(_) => {
            scan.skipped += 1;
            return Ok(());
        }
    };
    for dir in dirs {
        if has_level(&dir) {
            add_world(dir, group, scan, cancel)?;
        }
    }
    Ok(())
}

fn versions(
    path: &Path,
    scan: &mut WorldScan,
    cancel: &dyn Cancellation,
    budget: &mut usize,
) -> Result<(), CoreError> {
    for dir in children(path, cancel, budget)? {
        if WorldSource::new(dir.join("saves")).is_ok() {
            saves(&dir.join("saves"), &label(&dir), scan, cancel, budget)?;
        }
    }
    Ok(())
}

pub fn scan_selected_path(path: &Path, cancel: &dyn Cancellation) -> Result<WorldScan, CoreError> {
    cancel.check()?;
    let _guard = WorldSource::new(path)?;
    let mut scan = WorldScan::default();
    let mut budget = MAX_ENTRIES;
    if has_level(path) {
        add_world(path.to_owned(), &label(path), &mut scan, cancel)?;
    } else if label(path).eq_ignore_ascii_case("saves")
        || children(path, cancel, &mut budget)?
            .iter()
            .any(|dir| has_level(dir))
    {
        saves(path, &label(path), &mut scan, cancel, &mut budget)?;
    } else if WorldSource::new(path.join("saves")).is_ok()
        || WorldSource::new(path.join("versions")).is_ok()
    {
        if WorldSource::new(path.join("saves")).is_ok() {
            saves(
                &path.join("saves"),
                "默认存档 (Default)",
                &mut scan,
                cancel,
                &mut budget,
            )?;
        }
        if WorldSource::new(path.join("versions")).is_ok() {
            versions(&path.join("versions"), &mut scan, cancel, &mut budget)?;
        }
    } else {
        versions(path, &mut scan, cancel, &mut budget)?;
    }
    cancel.check()?;
    Ok(scan)
}
