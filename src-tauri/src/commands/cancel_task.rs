use crate::{
    error::{AppError, ErrorCode},
    tasks::TaskState,
};
use serde::Serialize;
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelStatus {
    cancelled: bool,
}
#[tauri::command]
pub(super) fn cancel_active_task<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<CancelStatus, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &[])?;
    Ok(CancelStatus {
        cancelled: tasks.cancel_current(),
    })
}
