use crate::{
    error::{AppError, ErrorCode},
    protocol::encode_map_tile_v1,
    tasks::TaskState,
    world_session::WorldSessionState,
};

#[tauri::command]
#[allow(clippy::too_many_arguments)] // Tauri 将每个严格 IPC 字段注入为独立参数。
pub(super) async fn load_map_tile<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    session_id: u64,
    tile_x: i32,
    tile_z: i32,
    blocks_per_pixel: u8,
    sessions: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<tauri::ipc::Response, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &["sessionId", "tileX", "tileZ", "blocksPerPixel"])?;
    let map_request = mcwe_core::map::MapTileRequest {
        tile_x,
        tile_z,
        blocks_per_pixel,
    };
    map_request.bounds().map_err(AppError::from)?;
    let session = sessions.get(session_id)?;
    let receipt = tasks.submit(session.id, move |worker| {
        mcwe_core::map::load_map_tile_cached(
            &session.source,
            &session.map_cache,
            map_request,
            &worker,
        )
    })?;
    let task_id = receipt.task_id();
    let tile = tauri::async_runtime::spawn_blocking(move || receipt.wait())
        .await
        .map_err(|_| AppError::new(ErrorCode::WorkerFailed))??;
    if !sessions.is_current(session_id) {
        return Err(AppError::new(ErrorCode::SessionOutdated));
    }
    Ok(tauri::ipc::Response::new(encode_map_tile_v1(
        &tile, session_id, task_id,
    )?))
}
