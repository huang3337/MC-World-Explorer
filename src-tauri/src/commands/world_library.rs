use crate::{
    error::{AppError, ErrorCode},
    tasks::TaskState,
    world_session::{WorldSessionState, WorldSummary},
};
use mcwe_core::world::scanner::{scan_selected_path, WorldScan};
use serde::Serialize;
use std::{collections::BTreeMap, path::PathBuf, sync::Mutex};
use tauri_plugin_dialog::DialogExt;

#[derive(Default)]
pub(crate) struct WorldLibrary(Mutex<Catalog>);
#[derive(Default)]
struct Catalog {
    generation: u64,
    last_id: u64,
    paths: BTreeMap<u64, PathBuf>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Entry {
    id: u64,
    name: String,
    group: String,
    display_path: PathBuf,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ScanResult {
    root_path: PathBuf,
    worlds: Vec<Entry>,
    skipped: usize,
}

impl WorldLibrary {
    fn begin(&self) -> Result<u64, AppError> {
        let mut catalog = self.0.lock().unwrap_or_else(|p| p.into_inner());
        catalog.generation = catalog
            .generation
            .checked_add(1)
            .ok_or_else(|| AppError::new(ErrorCode::SessionIdExhausted))?;
        Ok(catalog.generation)
    }
    fn publish(
        &self,
        generation: u64,
        root_path: PathBuf,
        scan: WorldScan,
    ) -> Result<ScanResult, AppError> {
        let mut catalog = self.0.lock().unwrap_or_else(|p| p.into_inner());
        if catalog.generation != generation {
            return Err(AppError::new(ErrorCode::TaskOutdated));
        }
        if catalog.last_id.saturating_add(scan.worlds.len() as u64)
            > crate::protocol::MAX_SAFE_INTEGER_ID
        {
            return Err(AppError::new(ErrorCode::SessionIdExhausted));
        }
        let mut paths = BTreeMap::new();
        let worlds = scan
            .worlds
            .into_iter()
            .map(|world| {
                catalog.last_id += 1;
                let id = catalog.last_id;
                paths.insert(id, world.path.clone());
                Entry {
                    id,
                    name: world.name,
                    group: world.group,
                    display_path: world.path,
                }
            })
            .collect();
        catalog.paths = paths;
        Ok(ScanResult {
            root_path,
            worlds,
            skipped: scan.skipped,
        })
    }
    fn path(&self, id: u64) -> Result<PathBuf, AppError> {
        self.0
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .paths
            .get(&id)
            .cloned()
            .ok_or_else(|| AppError::new(ErrorCode::SessionOutdated))
    }
}

#[tauri::command]
pub(super) async fn scan_world_directory<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    app: tauri::AppHandle<R>,
    request: tauri::ipc::Request<'_>,
    library: tauri::State<'_, WorldLibrary>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<Option<ScanResult>, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &[])?;
    let Some(folder) = app.dialog().file().blocking_pick_folder() else {
        return Ok(None);
    };
    let path = folder
        .into_path()
        .map_err(|_| AppError::new(ErrorCode::InvalidPath))?;
    let generation = library.begin()?;
    let scan_path = path.clone();
    let receipt = tasks.submit(0, move |worker| scan_selected_path(&scan_path, &worker))?;
    let scan = tauri::async_runtime::spawn_blocking(move || receipt.wait())
        .await
        .map_err(|_| AppError::new(ErrorCode::WorkerFailed))??;
    Ok(Some(library.publish(generation, path, scan)?))
}

#[tauri::command]
pub(super) async fn open_discovered_world<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    world_id: u64,
    library: tauri::State<'_, WorldLibrary>,
    state: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<Option<WorldSummary>, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &["worldId"])?;
    let path = library.path(world_id)?;
    super::select_world::apply_selected_path(&state, &tasks, Some(path)).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use mcwe_core::world::scanner::DiscoveredWorld;
    fn scan() -> WorldScan {
        WorldScan {
            worlds: vec![DiscoveredWorld {
                path: PathBuf::from("world"),
                group: "instance".into(),
                name: "world".into(),
            }],
            skipped: 0,
        }
    }
    #[test]
    fn stale_results_and_unlisted_ids_are_rejected() {
        let library = WorldLibrary::default();
        let first = library.begin().unwrap();
        let second = library.begin().unwrap();
        assert!(library.publish(first, PathBuf::new(), scan()).is_err());
        let result = library.publish(second, PathBuf::new(), scan()).unwrap();
        let old_id = result.worlds[0].id;
        assert!(library.path(old_id).is_ok());
        assert!(library.path(old_id + 1).is_err());
        let third = library.begin().unwrap();
        library.publish(third, PathBuf::new(), scan()).unwrap();
        assert!(library.path(old_id).is_err());
    }
}
