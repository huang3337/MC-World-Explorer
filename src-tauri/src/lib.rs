mod commands;
pub mod error;
pub mod storage;
pub mod tasks;

use tauri::Manager;

#[derive(Debug)]
pub struct StartupError;

fn coordinate_main_close<P, R, E>(label: &str, prevent: P, release: R, exit: E) -> bool
where
    P: FnOnce(),
    R: FnOnce(),
    E: FnOnce(),
{
    if label != "main" {
        return false;
    }

    prevent();
    release();
    exit();
    true
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() -> Result<(), StartupError> {
    let executable = std::env::current_exe().map_err(|_| StartupError)?;
    let exe_name = executable
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or(StartupError)?;
    let paths = storage::PortablePaths::from_executable(&executable).map_err(|_| StartupError)?;
    storage::detect_webview_overrides(exe_name, "com.mcworldexplorer.app")
        .map_err(|_| StartupError)?;
    let prepared = storage::PreparedWebviewData::prepare(&paths).map_err(|_| StartupError)?;
    let webview_data = prepared.path().to_path_buf();

    let app = commands::configure(tauri::Builder::default(), Ok(paths))
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                coordinate_main_close(
                    window.label(),
                    || api.prevent_close(),
                    || {
                        if let Some(lifecycle) = window.try_state::<storage::WebviewDataLifecycle>()
                        {
                            lifecycle.release();
                        }
                    },
                    || window.app_handle().exit(0),
                );
            }
        })
        .setup(move |app| {
            use tauri::Manager;
            let window_config = app
                .config()
                .app
                .windows
                .iter()
                .find(|window| window.label == "main")
                .cloned()
                .ok_or_else(|| std::io::Error::other("missing main window configuration"))?;
            app.manage(storage::WebviewDataLifecycle::new(prepared));
            tauri::WebviewWindowBuilder::from_config(app.handle(), &window_config)?
                .data_directory(webview_data.clone())
                .build()?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .map_err(|_| StartupError)?;

    app.run(|app, event| {
        if matches!(event, tauri::RunEvent::ExitRequested { .. }) {
            if let Some(lifecycle) = app.try_state::<storage::WebviewDataLifecycle>() {
                lifecycle.release();
            }
        }
    });
    Ok(())
}

#[cfg(test)]
mod shutdown_tests {
    use super::coordinate_main_close;
    use std::sync::Mutex;

    #[test]
    fn main_close_runs_prevent_release_exit_in_order() {
        let calls = Mutex::new(Vec::new());
        assert!(coordinate_main_close(
            "main",
            || calls.lock().unwrap().push("prevent"),
            || calls.lock().unwrap().push("release"),
            || calls.lock().unwrap().push("exit"),
        ));
        assert_eq!(*calls.lock().unwrap(), ["prevent", "release", "exit"]);
    }

    #[test]
    fn another_window_does_not_release_or_exit() {
        assert!(!coordinate_main_close(
            "other",
            || panic!(),
            || panic!(),
            || panic!()
        ));
    }
}
