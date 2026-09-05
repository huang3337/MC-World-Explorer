# 代码审查：webview_data.rs

- **审查日期**：2026-09-05
- **审查工具**：Codex
- **审查范围**：V0.7.1 Task 14 最终审查，WebView 便携目录安全测试
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-WEBVIEWDATA-001：权限不足时符号链接测试被报告为通过

- **严重程度**：🟡 中
- **类别**：测试可信度 / 安全边界
- **文件**：`src-tauri/src/storage/webview_data.rs`
- **行号**：审查时第 299–311 行
- **状态**：已修复

**问题描述**：`rejects_directory_symlink_without_writing_through_it` 在 Windows 无创建符号链接权限时打印“跳过”后直接 `return`。Rust 测试框架仍把该测试计为通过，无法从测试汇总区分安全边界已执行还是未执行。

**当前代码**（审查时快照）：
```rust
if let Err(error) = symlink_dir(&outside, root.join("config")) {
    eprintln!("跳过：当前权限不能创建目录符号链接：{error}");
    fs::remove_dir_all(root).unwrap();
    fs::remove_dir_all(outside).unwrap();
    return;
}
```

**问题分析**：项目已对 `mcwe-core` 的权限专项使用带原因的 `#[ignore]`，并把普通测试和提升权限专项结果分别报告。当前写法会产生“绿色但未验证”的结果，与路线中“条件跳过逐项说明”和 Rule 16 的安全测试可信度要求不一致。该问题不直接证明生产实现可以跟随链接，但会削弱 `DECISION-015` 的自动化证据。

**建议修改**：与核心测试保持一致，将权限专项改为显式 `#[ignore = "..."]`，移除运行时成功返回；普通回归必须显示 ignored，另在已授权、具备符号链接权限的环境用 `--include-ignored` 执行并记录结果。若能使用不需额外权限且语义等价的稳定夹具，也必须先证明它覆盖相同边界。

**影响范围**：应用层 WebView 目录重解析点拒绝测试及 V0.7.1 自动化证据；不影响已完成的真实便携落盘和关闭验收，不涉及 Minecraft 存档写入。

**解决日期**：2026-09-05。

**实际修改**：为该 Windows 权限专项增加带原因的 `#[ignore]`，移除权限不足时直接返回成功的路径；显式执行时若无法创建目录符号链接，会先清理自身夹具再明确失败。

**验证证据**：普通回归将该项报告为 1 项明确忽略，应用层其余 29 项通过；普通权限显式执行按预期失败并报告 Windows 1314，不再出现假通过；随后管理员权限使用 `--include-ignored --exact` 执行该精确测试，1 项通过、0 失败、0 忽略。Cargo 格式检查和全目标 Clippy `-D warnings` 同时通过。
