use crate::{
    error::{AppError, ErrorCode},
    storage::PortablePaths,
};
use serde::Serialize;
use std::path::PathBuf;

/// 只保留启动时查询的路径结果，不持有文件或写入能力。
pub(super) struct AppStatusState {
    paths: Result<PortablePaths, AppError>,
}

impl AppStatusState {
    pub(super) fn new(paths: std::io::Result<PortablePaths>) -> Self {
        Self {
            paths: paths.map_err(|error| AppError::from(mcwe_core::CoreError::from(error))),
        }
    }

    fn snapshot(&self) -> Result<AppStatusDto, AppError> {
        let paths = self.paths.as_ref().map_err(Clone::clone)?;
        Ok(AppStatusDto {
            app_version: env!("CARGO_PKG_VERSION"),
            backend_status: "ready",
            portable_paths: PortablePathsDto {
                root: paths.root().to_path_buf(),
                cache: paths.cache(),
                logs: paths.logs(),
                exports: paths.exports(),
                config: paths.config(),
            },
        })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct AppStatusDto {
    app_version: &'static str,
    backend_status: &'static str,
    portable_paths: PortablePathsDto,
}

#[derive(Serialize)]
struct PortablePathsDto {
    root: PathBuf,
    cache: PathBuf,
    logs: PathBuf,
    exports: PathBuf,
    config: PathBuf,
}

/// 无用户路径参数；只返回应用层状态，不解析或访问 Minecraft 文件。
#[tauri::command]
pub(super) fn app_status<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    state: tauri::State<'_, AppStatusState>,
) -> Result<AppStatusDto, AppError> {
    // 应用自定义 Command 不由 core:default 自动限制到某个窗口。
    if window.label() != "main" {
        return Err(AppError::new(ErrorCode::PermissionDenied));
    }
    super::require_json_arguments(&request, &[])?;
    state.snapshot()
}

#[cfg(test)]
mod tests {
    use crate::{commands::configure, storage::PortablePaths};
    use serde_json::{json, Value};
    use tauri::{
        ipc::{CallbackFn, InvokeBody},
        test::{
            get_ipc_response, mock_builder, mock_context, noop_assets, MockRuntime, INVOKE_KEY,
        },
        webview::InvokeRequest,
    };

    fn request(command: &str, body: Value) -> InvokeRequest {
        InvokeRequest {
            cmd: command.into(),
            callback: CallbackFn(0),
            error: CallbackFn(1),
            url: "http://tauri.localhost".parse().unwrap(),
            body: InvokeBody::Json(body),
            headers: Default::default(),
            invoke_key: INVOKE_KEY.into(),
        }
    }

    fn app(paths: std::io::Result<PortablePaths>) -> tauri::App<MockRuntime> {
        configure(mock_builder(), paths)
            .build(mock_context(noop_assets()))
            .unwrap()
    }

    #[test]
    fn ipc_uses_managed_paths_and_returns_exact_dto_without_creating_directories() {
        let root = std::env::temp_dir().join(format!(
            "mcwe-ipc-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        assert!(!root.exists());
        let app = app(PortablePaths::from_executable(root.join("app.exe")));
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let response = get_ipc_response(&window, request("app_status", json!({})))
            .unwrap()
            .deserialize::<Value>()
            .unwrap();
        assert_eq!(
            response,
            json!({
                "appVersion": "0.7.3", "backendStatus": "ready",
                "portablePaths": {"root": root, "cache": root.join("cache"), "logs": root.join("logs"), "exports": root.join("exports"), "config": root.join("config")}
            })
        );
        // 无参数 Command 不静默接受伪造路径或版本字段。
        let error = get_ipc_response(
            &window,
            request(
                "app_status",
                json!({"root":"C:\\other", "appVersion":"fake"}),
            ),
        )
        .unwrap_err();
        assert_eq!(error["code"], "INVALID_ARGUMENTS");
        assert!(!root.exists());
    }

    #[test]
    fn path_failure_is_a_safe_ipc_error_instead_of_panic_or_fallback() {
        let app = app(Err(std::io::Error::other("C:\\private\\internal-stack")));
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let error = get_ipc_response(&window, request("app_status", json!({}))).unwrap_err();
        assert_eq!(error["code"], "IO_ERROR");
        assert!(!error.to_string().contains("private"));
    }

    #[test]
    fn another_window_cannot_read_app_paths() {
        let app = app(Err(std::io::Error::other("not used")));
        let window = tauri::WebviewWindowBuilder::new(&app, "other", Default::default())
            .build()
            .unwrap();
        let error = get_ipc_response(&window, request("app_status", json!({}))).unwrap_err();
        assert_eq!(error["code"], "PERMISSION_DENIED");
    }

    #[test]
    fn unknown_command_is_not_registered() {
        let app = app(Err(std::io::Error::other("not used")));
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        assert!(get_ipc_response(&window, request("read_world", json!({}))).is_err());
    }

    #[test]
    fn remote_origin_is_not_allowed_to_invoke_status() {
        let app = app(Err(std::io::Error::other("not used")));
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let mut remote = request("app_status", json!({}));
        remote.url = "https://example.com".parse().unwrap();
        assert!(get_ipc_response(&window, remote).is_err());
    }
}
