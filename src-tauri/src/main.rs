#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    if mc_world_explorer_lib::run().is_err() {
        show_startup_error();
        std::process::exit(1);
    }
}

#[cfg(windows)]
fn show_startup_error() {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};
    let title: Vec<u16> = "MC World Explorer\0".encode_utf16().collect();
    let message: Vec<u16> = "MC World Explorer 启动失败。请确认程序位于可写入的便携目录，且未放在 Minecraft 世界目录内；同时请检查 WebView2 策略设置。\0"
        .encode_utf16()
        .collect();
    // SAFETY: 两个 UTF-16 缓冲区均以 NUL 结尾，并在同步 MessageBoxW 调用期间保持有效；无父窗口句柄。
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            message.as_ptr(),
            title.as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}

#[cfg(not(windows))]
fn show_startup_error() {
    eprintln!("MC World Explorer 启动失败");
}
