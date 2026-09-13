# 代码审查：src-tauri/src/lib.rs（Task 10）

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 主窗口关闭与执行器停止顺序
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-TAURILIB-003：关闭流程不能阻止新任务且没有有界失败路径

- **严重程度**：🟠 高
- **类别**：资源管理
- **文件**：`src-tauri/src/lib.rs`
- **状态**：已修复

**问题描述**：
初版关闭时取消登记器并等待 worker gate，随后无条件释放 WebView 数据和退出；等待期间没有 shutdown 状态阻止新请求，也没有超时分支。

**问题分析**：
新请求可能在 gate 等待完成后再次进入，且不响应取消的 worker 会让窗口事件无限阻塞。未确认 worker 析构前不得释放数据或退出。

**建议修改**：
先进入 shutdown、拒绝新任务、取消 active/pending，并进行有界等待；只有 worker 确认退出后才能释放 WebView 数据和退出，超时则阻止退出。

**影响范围**：
主窗口关闭、系统退出请求、世界句柄和便携 WebView 数据保护。

- **解决日期**：2026-09-07。
- **实际修改**：关闭顺序改为 prevent→shutdown/wait→release→exit；超时只执行 prevent＋shutdown，`ExitRequested` 同样可阻止退出。
- **验证证据**：完整顺序、非 main 窗口、超时不释放/不退出和 shutdown 竞态测试通过。
