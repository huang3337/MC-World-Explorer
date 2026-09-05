# 代码审查：lib.rs

- **审查日期**：2026-09-05
- **审查工具**：Codex
- **审查范围**：Task 13 WebView 便携数据与正常关闭验收
- **问题总数**：2 个（🔴 0 / 🟠 2 / 🟡 0 / 🟢 0）

### ISSUE-TAURILIB-001：默认 WebView 数据目录落入 LocalAppData

- **严重程度**：🟠 高
- **类别**：安全性 / 便携性边界
- **文件**：`src-tauri/src/lib.rs`（关联 `src-tauri/tauri.conf.json`）
- **行号**：审查时第 6–14 行
- **状态**：已修复并经 Task 13 实际启动验证

**问题描述**：应用只查询 PortablePaths，但未将便携目录设置给实际 WebView。默认窗口创建会使用 LocalAppData，与不写 AppData 的已确认约束冲突，阻断 Task 13 启动验收。

**当前代码**（审查时快照）：
```rust
pub fn run() {
    commands::configure(
        tauri::Builder::default(),
        storage::PortablePaths::from_current_executable(),
    )
    .run(tauri::generate_context!())
    .expect("MC World Explorer 启动失败");
}
```

**问题分析**：本机锁定 Tauri 2.11.5 的 `src/manager/webview.rs:534–551` 在 data_directory 未设置时使用 LocalData 加 identifier，并对不存在的目录执行 create_dir_all；`src/path/desktop.rs:81–87` 说明 Windows LocalData 为 FOLDERID_LocalAppData。当前配置和启动代码均未覆盖此设置，默认目标为 LocalAppData 下的 `com.mcworldexplorer.app`。这是源码路径证据，本次未启动 EXE 或以实际写入复现，也不反推历史模板验收结果。

**建议修改**：先确认方案与实施计划，再在真实 WebView 创建前显式设置 EXE 便携目录内的数据位置（候选 `config/webview/`）。需处理目录创建、重解析点/世界重叠、失败不回退、已有环境覆盖和正常关闭，不能只设置显示 DTO 或用验收脚本临时变量掩盖默认行为。

**影响范围**：实际 WebView 启动及运行时数据落盘；现有 mock 和浏览器模拟测试不覆盖该默认路径。修复后需重新全量验证、构建并重做 Task 13。单 EXE 分发仍可保留，运行产生的数据目录不等于附带运行时或 sidecar。

**修复进度**：已按 `DECISION-015` 禁止配置自动创建窗口，在启动层检查 WebView2 环境和注册表覆盖、准备并保护 `EXE/config/webview/`，再显式设置 `data_directory` 创建 main 窗口；失败使用固定中文原生弹窗，不回退其他目录。应用层 25 项、核心普通权限 10 项与 2 项编译失败文档测试、前端 14 项测试及前端构建已通过；2 项既有核心权限专项仍明确忽略。

**重新构建证据**：2026-09-05 已执行 `npm run tauri build -- --no-bundle -- --locked --offline`，退出码 0。新产物为 `target/release/mc-world-explorer.exe`，8,719,872 字节，SHA-256 `5E8FCD58AC7769CA0A6BBC742A775E1E64179E30013CAE2C1B4AB31D42D2A86A`，PE32+ x64 Windows GUI；未生成 bundle。旧哈希 `5030A6E821F0485D93FEE42B8B1478E45B2D079B1343586286F91BDD07F86B0F` 已被替代，不得用于本次通过结论。

**实际验证**：新 EXE 从只含自身的干净临时目录、以 `C:\Windows\System32` 为 CWD 启动。WebView2 主进程参数中的 `--user-data-dir` 明确指向 `EXE/config/webview/EBWebView`，便携目录实际产生 159 个文件；既有 `LocalAppData/com.mcworldexplorer.app` 前后均为 184 个文件、16,081,627 字节，最新修改时间保持 `2026-08-24T04:41:00.4752637Z`。开发者人工确认页面显示后端就绪、版本和正确程序根目录。因此本问题已修复；关闭残留另记 `ISSUE-TAURILIB-002`。

- **解决日期**：2026-09-05
- **实际修改**：禁止配置自动创建窗口，启动层完成便携目录安全准备并显式设置 WebView `data_directory`；失败不回退 AppData。
- **验证证据**：两轮真实 EXE 验收均确认 `--user-data-dir` 指向各自夹具内的 `config/webview/EBWebView`，既有 AppData 基线始终未变化。

### ISSUE-TAURILIB-002：关闭最后窗口后主进程残留并持续持有便携目录

- **严重程度**：🟠 高
- **类别**：生命周期 / 资源释放
- **文件**：`src-tauri/src/lib.rs`
- **行号**：发现时第 10–42 行
- **状态**：已修复并经 Task 13 实际关闭验证

**问题描述**：Task 13 中无论通过 `CloseMainWindow()` 请求关闭，还是开发者手动点击窗口关闭按钮，最后窗口消失、WebView2 子进程退出后，主进程仍持续运行。残留进程没有主窗口，但仍持有 `config/webview` 目录句柄，导致目录无法改名或清理。

**实际证据**：测试主进程 PID 33624。自动正常关闭请求等待 15 秒后仍未退出；随后人工关闭窗口，主窗口句柄变为 0，WebView2 子进程全部消失，但主进程仍有 39 个线程、340 个句柄且可响应。此时可逆改名探测返回文件被占用。仅在核对进程路径为本次临时 EXE 后强制结束该 PID，目录改名才成功。

**问题分析**：Tauri runtime 源码通常会在最后一个窗口 `Destroyed` 后请求事件循环退出；当前现象说明本应用的动态窗口创建/退出链路没有达到该终态。现阶段证据不足以断言是 Tauri runtime、动态窗口登记、目录保护句柄或应用事件处理中的哪一环，不应直接以强制结束作为产品行为。

**建议修改**：先对最小动态窗口生命周期增加可观察测试，确认 `Destroyed`、窗口登记清理与 `ExitRequested` 顺序；必要时在应用层为 main 最后窗口增加明确且幂等的退出处理，同时保证 Tauri cleanup 能执行并释放 `PreparedWebviewData`。修复、自动化和重新构建后必须重做正常关闭、子进程及目录释放验收。

**影响范围**：用户关闭窗口后应用可能成为无界面后台残留，阻止移动、覆盖或清理便携目录，也使单 EXE 生命周期验收不通过。Task 13 和 V0.7.1 均保持未完成。

**实施进度**：已按 `DECISION-016` 新增 `WebviewDataLifecycle`，使用 `Mutex<Option<PreparedWebviewData>>` 在锁外幂等释放保护句柄；main 的 `CloseRequested` 按“阻止默认关闭 → 释放句柄 → Tauri 正常退出”执行，`ExitRequested` 使用相同释放作为兜底，非 main 窗口不触发全应用退出。未使用 `std::process::exit`，未修改 WebView 路径、安全检查、依赖或 capability。

**自动化证据**：生命周期和关闭协调新增 5 项测试；应用层共 30 项、核心普通权限 10 项及 2 项编译失败文档测试、前端 14 项测试全部通过，2 项既有核心权限专项仍明确忽略。Cargo 格式检查、全目标 Clippy `-D warnings`、前端类型检查与生产构建通过，`mcwe-core` 依赖树仍只有自身。该证据不等于真实 Tauri 事件链或主进程退出已经通过，因此问题保持活动。

**重新构建证据**：经开发者单独确认，执行 `npm run tauri build -- --no-bundle -- --locked --offline` 成功。新产物 `target/release/mc-world-explorer.exe` 为 8,714,752 字节，SHA-256 `39CA7E88C60970B22ACE96B9085D7314E6A9941EB91D5EC923C4D03A77C003AC`，PE32+ x64 Windows GUI；未生成 bundle 或源码目录下的 `config/`。构建完成时尚未启动该 EXE；后续真实验证结果见下方验证证据。

- **解决日期**：2026-09-05
- **实际修改**：按 `DECISION-016` 在 main 的 `CloseRequested` 阻止默认关闭，先通过并发安全、幂等的生命周期状态释放目录保护句柄，再请求 Tauri 正常退出；`ExitRequested` 重复释放作为其他退出路径兜底。
- **验证证据**：新 EXE 从仅含自身的 `target/task13-portable-20260905-1238/`、以 `C:\Windows\System32` 为 CWD 启动；主 PID 34124 运行时响应正常，WebView2 使用夹具内 profile，开发者确认真实页面显示后端就绪、版本 `0.7.1` 和正确根路径。人工关闭后第一次检查即确认主进程已自然退出，关联 MCWE/WebView2 进程为 0；profile 可成功改名并恢复。AppData 前后均为 184 个文件、16,081,627 字节，最新修改时间 `2026-08-24T04:41:00.4752637Z`。本次未使用强制终止；精确临时夹具在确认无关联进程后删除并验证不存在，release EXE 保留。
