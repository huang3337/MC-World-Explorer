fn main() {
    tauri_build::build();
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        // Tauri 的应用资源清单不自动进入 lib 单元测试 EXE。
        // mock runtime 仍链接 Common Controls v6 的 TaskDialogIndirect。
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTDEPENDENCY:type='win32' name='Microsoft.Windows.Common-Controls' version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'");
        // bin 已通过 Tauri 的 resource.lib 携带完整清单，禁止链接器再生成一份。
        println!("cargo:rustc-link-arg-bins=/MANIFEST:NO");
    }
}
