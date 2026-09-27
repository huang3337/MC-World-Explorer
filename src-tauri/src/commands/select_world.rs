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
    let summary = state.commit_candidate(candidate)?;
    tasks.abort_map_other_sessions(summary.session_id);
    Ok(Some(summary))
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
    use crate::{
        commands::configure,
        storage::PortablePaths,
        tasks::{MapJobKey, MapPriority},
    };
    use serde_json::{json, Value};
    use std::{
        collections::HashSet,
        fs,
        path::PathBuf,
        sync::{
            atomic::{AtomicU64, Ordering},
            mpsc,
        },
        time::Duration,
    };
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

    struct SyntheticWorlds {
        root: PathBuf,
        first: PathBuf,
        second: PathBuf,
    }

    impl SyntheticWorlds {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let base =
                PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/select-world-tests");
            fs::create_dir_all(&base).unwrap();
            let root = base.canonicalize().unwrap().join(format!(
                "switch-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let first = root.join("first");
            let second = root.join("second");
            for world in [&first, &second] {
                fs::create_dir_all(world.join("region")).unwrap();
                fs::write(world.join("level.dat"), synthetic_level()).unwrap();
            }
            Self {
                root,
                first,
                second,
            }
        }
    }

    impl Drop for SyntheticWorlds {
        fn drop(&mut self) {
            for world in [&self.first, &self.second] {
                let _ = fs::remove_file(world.join("level.dat"));
                let _ = fs::remove_dir(world.join("region"));
                let _ = fs::remove_dir(world);
            }
            let _ = fs::remove_dir(&self.root);
        }
    }

    fn synthetic_level() -> Vec<u8> {
        let mut bytes = vec![10, 0, 0, 10, 0, 4];
        bytes.extend_from_slice(b"Data");
        for (name, value) in [("SpawnX", 0_i32), ("SpawnY", 64), ("SpawnZ", 0)] {
            bytes.extend_from_slice(&[3, 0, name.len() as u8]);
            bytes.extend_from_slice(name.as_bytes());
            bytes.extend_from_slice(&value.to_be_bytes());
        }
        bytes.extend_from_slice(&[0, 0]);
        bytes
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

    #[test]
    fn successful_world_switch_cancels_old_map_poll_but_failed_candidate_keeps_it() {
        let fixture = SyntheticWorlds::new();
        let sessions = WorldSessionState::new(&Err(std::io::Error::other("not used")));
        let tasks = TaskState::default();
        let first = tauri::async_runtime::block_on(apply_selected_path(
            &sessions,
            &tasks,
            Some(fixture.first.clone()),
        ))
        .unwrap()
        .unwrap();
        let viewport = tasks
            .begin_map_viewport(first.session_id, &HashSet::new())
            .unwrap();
        tasks.track_map_viewport(first.session_id, viewport, 1, Vec::new());
        let (started_tx, started_rx) = mpsc::channel();
        let (cancelled_tx, cancelled_rx) = mpsc::channel();
        tasks
            .submit_map(
                viewport,
                MapJobKey::new(first.session_id, 0, 0, 1).unwrap(),
                MapPriority::visible(0, 0, 0),
                move |worker| {
                    started_tx.send(()).unwrap();
                    while !worker.is_cancelled() {
                        std::thread::yield_now();
                    }
                    cancelled_tx.send(()).unwrap();
                },
            )
            .unwrap();
        started_rx.recv_timeout(Duration::from_secs(1)).unwrap();

        assert!(tauri::async_runtime::block_on(apply_selected_path(
            &sessions,
            &tasks,
            Some(fixture.root.join("missing"))
        ))
        .is_err());
        assert!(matches!(
            cancelled_rx.try_recv(),
            Err(mpsc::TryRecvError::Empty)
        ));
        assert!(tasks.begin_map_poll(first.session_id, viewport).is_ok());

        let poll = tasks.begin_map_poll(first.session_id, viewport).unwrap();
        let second = tauri::async_runtime::block_on(apply_selected_path(
            &sessions,
            &tasks,
            Some(fixture.second.clone()),
        ))
        .unwrap()
        .unwrap();
        assert_ne!(second.session_id, first.session_id);
        assert!(matches!(
            poll.wait(Duration::from_millis(50)),
            Err(crate::tasks::MapPollError::Outdated)
        ));
        cancelled_rx.recv_timeout(Duration::from_secs(1)).unwrap();

        let new_viewport = tasks
            .begin_map_viewport(second.session_id, &HashSet::new())
            .unwrap();
        tasks.track_map_viewport(second.session_id, new_viewport, 1, Vec::new());
        let stale_start = sessions.with_current(first.session_id, || {
            tasks.begin_map_viewport(first.session_id, &HashSet::new())
        });
        assert_eq!(
            serde_json::to_value(stale_start.unwrap_err()).unwrap()["code"],
            "SESSION_OUTDATED"
        );
        assert!(tasks
            .begin_map_poll(second.session_id, new_viewport)
            .is_ok());
    }
}
