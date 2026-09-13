mod commands;
pub mod error;
pub mod protocol;
pub mod storage;
pub mod tasks;
mod world_session;

use std::time::Duration;
use tauri::Manager;

const SHUTDOWN_WAIT: Duration = Duration::from_secs(5);

#[derive(Debug)]
pub struct StartupError;

fn coordinate_main_close<P, S, R, E>(
    label: &str,
    prevent: P,
    shutdown: S,
    release: R,
    exit: E,
) -> bool
where
    P: FnOnce(),
    S: FnOnce() -> bool,
    R: FnOnce(),
    E: FnOnce(),
{
    if label != "main" {
        return false;
    }

    prevent();
    if !shutdown() {
        return false;
    }
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
                        if let Some(tasks) = window.try_state::<tasks::TaskState>() {
                            tasks.shutdown(SHUTDOWN_WAIT)
                        } else {
                            true
                        }
                    },
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
        if let tauri::RunEvent::ExitRequested { api, .. } = event {
            let stopped = app
                .try_state::<tasks::TaskState>()
                .is_none_or(|tasks| tasks.shutdown(SHUTDOWN_WAIT));
            if stopped {
                if let Some(lifecycle) = app.try_state::<storage::WebviewDataLifecycle>() {
                    lifecycle.release();
                }
            } else {
                api.prevent_exit();
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
            || {
                calls.lock().unwrap().push("shutdown");
                true
            },
            || calls.lock().unwrap().push("release"),
            || calls.lock().unwrap().push("exit"),
        ));
        assert_eq!(
            *calls.lock().unwrap(),
            ["prevent", "shutdown", "release", "exit"]
        );
    }

    #[test]
    fn another_window_does_not_release_or_exit() {
        assert!(!coordinate_main_close(
            "other",
            || panic!(),
            || panic!(),
            || panic!(),
            || panic!()
        ));
    }

    #[test]
    fn shutdown_timeout_keeps_data_and_process_alive() {
        let calls = Mutex::new(Vec::new());
        assert!(!coordinate_main_close(
            "main",
            || calls.lock().unwrap().push("prevent"),
            || {
                calls.lock().unwrap().push("shutdown");
                false
            },
            || calls.lock().unwrap().push("release"),
            || calls.lock().unwrap().push("exit"),
        ));
        assert_eq!(*calls.lock().unwrap(), ["prevent", "shutdown"]);
    }
}
