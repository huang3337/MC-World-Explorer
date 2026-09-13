use crate::{
    error::{AppError, ErrorCode},
    protocol::encode_mesh_v1,
    tasks::TaskState,
    world_session::WorldSessionState,
};
use mcwe_core::{
    anvil::read_chunk_payload,
    cancel::Cancellation,
    chunk::decode_chunk,
    mesh::{build_mesh, ChunkFailure, ChunkRect, Neighborhood},
    CoreError,
};
use serde::Deserialize;
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChunkRectDto {
    min_x: i32,
    min_z: i32,
    width: u8,
    depth: u8,
}
#[tauri::command]
pub(super) async fn build_mesh_for_selection<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    session_id: u64,
    chunk_rect: ChunkRectDto,
    sessions: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<tauri::ipc::Response, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &["sessionId", "chunkRect"])?;
    let valid_rect_shape = match request.body() {
        tauri::ipc::InvokeBody::Json(value) => value
            .get("chunkRect")
            .and_then(|rect| rect.as_object())
            .is_some_and(|rect| {
                rect.len() == 4
                    && ["minX", "minZ", "width", "depth"]
                        .iter()
                        .all(|name| rect.contains_key(*name))
            }),
        _ => false,
    };
    if !valid_rect_shape {
        return Err(AppError::new(ErrorCode::InvalidArguments));
    }
    let rect = ChunkRect {
        min_x: chunk_rect.min_x,
        min_z: chunk_rect.min_z,
        width: chunk_rect.width,
        depth: chunk_rect.depth,
    };
    rect.validate()?;
    let session = sessions.get(session_id)?;
    rect.validate_for_preview(
        session.info.preview_center.position.x,
        session.info.preview_center.position.z,
    )?;
    let neighborhood_coordinates = rect.neighborhood_coordinates()?;
    let receipt = tasks.submit(session.id, move |worker| {
        worker.check()?;
        let mut neighborhood = Neighborhood::default();
        let mut retained_bytes = 0_usize;
        for c in neighborhood_coordinates {
            worker.check()?;
            let result = read_chunk_payload(&session.source, c, &worker)
                .and_then(|payload| payload.ok_or(CoreError::NotFound))
                .and_then(|payload| decode_chunk(&payload.bytes, c, &worker));
            match result {
                Ok(chunk) => {
                    let bytes = chunk
                        .sections
                        .values()
                        .map(|section| section.storage_budget_bytes())
                        .sum::<usize>();
                    retained_bytes = retained_bytes
                        .checked_add(bytes)
                        .ok_or(CoreError::ResourceLimit)?;
                    if retained_bytes > 512 * 1024 * 1024 {
                        return Err(CoreError::ResourceLimit);
                    }
                    neighborhood.chunks.insert(c, chunk);
                }
                Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
                Err(CoreError::NotFound) => {
                    neighborhood.failures.insert(c, ChunkFailure::Missing);
                }
                Err(CoreError::UnsupportedChunk | CoreError::UnsupportedCompression) => {
                    neighborhood.failures.insert(c, ChunkFailure::Unsupported);
                }
                Err(_) => {
                    neighborhood.failures.insert(c, ChunkFailure::Corrupt);
                }
            }
        }
        build_mesh(&neighborhood, rect, &worker)
    })?;
    let task_id = receipt.task_id();
    let mesh = tauri::async_runtime::spawn_blocking(move || receipt.wait())
        .await
        .map_err(|_| AppError::new(ErrorCode::WorkerFailed))??;
    if !sessions.is_current(session_id) {
        return Err(AppError::new(ErrorCode::SessionOutdated));
    }
    let bytes = encode_mesh_v1(&mesh, rect, session_id, task_id)?;
    Ok(tauri::ipc::Response::new(bytes))
}
