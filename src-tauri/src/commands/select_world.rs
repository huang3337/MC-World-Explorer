use crate::{
    error::{AppError, ErrorCode},
    tasks::TaskState,
    world_session::{validate_candidate, WorldSessionState, WorldSummary},
};
use tauri_plugin_dialog::DialogExt;

pub(super) async fn apply_selected_path(
    state: &WorldSessionState,
    tasks: &TaskState,
    path: Option<std::path::PathBuf>,
) -> Result<Option<WorldSummary>, AppError> {
    let Some(path) = path else {
        return Ok(None);
    };
    tasks.cancel_current();
    let portable_root = state.portable_root_for_validation();
    let receipt = tasks.submit(0, move |worker| {
        validate_candidate(path, portable_root, &worker)
    })?;
    let candidate = tauri::async_runtime::spawn_blocking(move || receipt.wait())
        .await
        .map_err(|_| AppError::new(ErrorCode::WorkerFailed))??;
    // 校验完成后再取消可能竞态进入的旧会话任务，然后一次性提交候选会话。
    tasks.cancel_current();
    Ok(Some(state.commit_candidate(candidate)?))
}

#[tauri::command]
pub(super) async fn select_world<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    app: tauri::AppHandle<R>,
    request: tauri::ipc::Request<'_>,
    state: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<Option<WorldSummary>, AppError> {
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &[])?;
    let folder = app.dialog().file().blocking_pick_folder();
    let path = folder
        .map(|selected| {
            selected
                .into_path()
                .map_err(|_| AppError::new(ErrorCode::InvalidPath))
        })
        .transpose()?;
    apply_selected_path(&state, &tasks, path).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{commands::configure, storage::PortablePaths};
    use serde_json::{json, Value};
    use tauri::{
        ipc::{CallbackFn, InvokeBody},
        test::{
            get_ipc_response, mock_builder, mock_context, noop_assets, MockRuntime, INVOKE_KEY,
        },
        webview::InvokeRequest,
    };

    fn request(body: Value) -> InvokeRequest {
        InvokeRequest {
            cmd: "select_world".into(),
            callback: CallbackFn(0),
            error: CallbackFn(1),
            url: "http://tauri.localhost".parse().unwrap(),
            body: InvokeBody::Json(body),
            headers: Default::default(),
            invoke_key: INVOKE_KEY.into(),
        }
    }

    fn app() -> tauri::App<MockRuntime> {
        configure(
            mock_builder(),
            Err(std::io::Error::other("paths unavailable in test")),
        )
        .build(mock_context(noop_assets()))
        .unwrap()
    }

    #[test]
    fn dialog_cancellation_preserves_active_task_and_session_state() {
        let paths: Result<PortablePaths, _> = Err(std::io::Error::other("not used"));
        let sessions = WorldSessionState::new(&paths);
        let tasks = TaskState::default();
        let task = tasks.submit(1, |_| Ok(7_u8)).unwrap();
        assert!(
            tauri::async_runtime::block_on(apply_selected_path(&sessions, &tasks, None))
                .unwrap()
                .is_none()
        );
        assert_eq!(task.wait().unwrap(), 7);
        let error = sessions.get(1).err().unwrap();
        assert_eq!(
            serde_json::to_value(error).unwrap()["code"],
            "NO_ACTIVE_WORLD"
        );
    }

    #[test]
    fn extra_arguments_are_rejected_before_opening_dialog() {
        let app = app();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let error = get_ipc_response(
            &window,
            request(json!({"path":"C:\\untrusted", "displayPath":"C:\\fake"})),
        )
        .unwrap_err();
        assert_eq!(error["code"], "INVALID_ARGUMENTS");
    }

    #[test]
    fn another_window_and_remote_origin_cannot_select_world() {
        let app = app();
        let other = tauri::WebviewWindowBuilder::new(&app, "other", Default::default())
            .build()
            .unwrap();
        let error = get_ipc_response(&other, request(json!({}))).unwrap_err();
        assert_eq!(error["code"], "PERMISSION_DENIED");

        let main = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let mut remote = request(json!({}));
        remote.url = "https://example.com".parse().unwrap();
        assert!(get_ipc_response(&main, remote).is_err());
    }

    #[test]
    fn candidate_validation_cancels_active_task_even_when_candidate_fails() {
        let paths: Result<PortablePaths, _> = Err(std::io::Error::other("not used"));
        let sessions = WorldSessionState::new(&paths);
        let tasks = TaskState::default();
        let task = tasks
            .submit(1, |task| {
                while !task.is_cancelled() {
                    std::thread::yield_now();
                }
                Err::<u8, _>(mcwe_core::CoreError::Cancelled)
            })
            .unwrap();
        assert!(tauri::async_runtime::block_on(apply_selected_path(
            &sessions,
            &tasks,
            Some(std::path::PathBuf::from("relative-invalid"))
        ))
        .is_err());
        assert!(task.wait().is_err());
    }
}
