mod app_status;

/// 生产与 mock runtime 共用唯一的应用状态与 Command 注册入口。
pub(crate) fn configure<R: tauri::Runtime>(
    builder: tauri::Builder<R>,
    paths: std::io::Result<crate::storage::PortablePaths>,
) -> tauri::Builder<R> {
    builder
        .manage(app_status::AppStatusState::new(paths))
        .invoke_handler(tauri::generate_handler![app_status::app_status])
}
