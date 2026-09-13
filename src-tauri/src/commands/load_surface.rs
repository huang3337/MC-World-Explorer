use crate::{
    error::{AppError, ErrorCode},
    protocol::encode_surface_v1,
    tasks::TaskState,
    world_session::WorldSessionState,
};
#[tauri::command]
pub(super) async fn load_surface<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    session_id: u64,
    sessions: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<tauri::ipc::Response, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &["sessionId"])?;
    let session = sessions.get(session_id)?;
    let center = (
        session.info.preview_center.position.x,
        session.info.preview_center.position.y,
        session.info.preview_center.position.z,
    );
    let center_source = match session.info.preview_center.source {
        mcwe_core::world::PreviewCenterSource::PlayerRespawn => "playerRespawn",
        mcwe_core::world::PreviewCenterSource::WorldSpawn => "worldSpawn",
        mcwe_core::world::PreviewCenterSource::OriginFallback => "originFallback",
    };
    let world_spawn = session
        .info
        .world_spawn
        .as_ref()
        .map(|position| (position.x, position.y, position.z));
    let receipt = tasks.submit(session.id, move |worker| {
        mcwe_core::cancel::Cancellation::check(&worker)?;
        mcwe_core::area::load_surface_area(&session.source, &session.info, &worker)
    })?;
    let task_id = receipt.task_id();
    let area = tauri::async_runtime::spawn_blocking(move || receipt.wait())
        .await
        .map_err(|_| AppError::new(ErrorCode::WorkerFailed))??;
    if !sessions.is_current(session_id) {
        return Err(AppError::new(ErrorCode::SessionOutdated));
    }
    let data = encode_surface_v1(
        &area,
        session_id,
        task_id,
        center,
        center_source,
        world_spawn,
    )?;
    Ok(tauri::ipc::Response::new(data))
}
