mod app_status;
mod build_mesh;
mod cancel_task;
mod load_map_tile;
mod load_surface;
mod select_world;
mod world_library;

#[cfg(all(test, windows))]
mod tests;

use crate::error::{AppError, ErrorCode};

fn require_json_arguments(
    request: &tauri::ipc::Request<'_>,
    expected: &[&str],
) -> Result<(), AppError> {
    match request.body() {
        tauri::ipc::InvokeBody::Json(value)
            if value.as_object().is_some_and(|arguments| {
                arguments.len() == expected.len()
                    && expected.iter().all(|name| arguments.contains_key(*name))
            }) =>
        {
            Ok(())
        }
        _ => Err(AppError::new(ErrorCode::InvalidArguments)),
    }
}

/// 生产与 mock runtime 共用唯一的应用状态与 Command 注册入口。
pub(crate) fn configure<R: tauri::Runtime>(
    builder: tauri::Builder<R>,
    paths: std::io::Result<crate::storage::PortablePaths>,
) -> tauri::Builder<R> {
    let sessions = crate::world_session::WorldSessionState::new(&paths);
    builder
        .plugin(tauri_plugin_dialog::init())
        .manage(app_status::AppStatusState::new(paths))
        .manage(sessions)
        .manage(world_library::WorldLibrary::default())
        .manage(crate::tasks::TaskState::default())
        .invoke_handler(tauri::generate_handler![
            app_status::app_status,
            select_world::select_world,
            world_library::scan_world_directory,
            world_library::open_discovered_world,
            load_surface::load_surface,
            load_map_tile::load_map_tile,
            build_mesh::build_mesh_for_selection,
            cancel_task::cancel_active_task
        ])
}
