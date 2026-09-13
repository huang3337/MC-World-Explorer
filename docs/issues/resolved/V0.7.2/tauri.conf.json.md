# 代码审查：src-tauri/tauri.conf.json

- **审查日期**：2026-09-09
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 16 WebView 内容安全策略
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-TAURICONF-001：生产 WebView 未启用 CSP

- **严重程度**：🟠 高
- **类别**：WebView 安全
- **文件**：`src-tauri/tauri.conf.json`
- **状态**：已修复

**问题描述**：`app.security.csp` 为 `null`，生产资源没有内容安全策略保护。

**实际修改**：生产 CSP 限制默认资源为自身，仅允许 Tauri IPC connect、同源/data 图片和同源样式；开发态 `devCsp` 保持 `null`，不把 Vite HMR 需求放宽到生产策略。

- **解决日期**：2026-09-09。
- **验证证据**：Tauri 配置由 Cargo 编译/Clippy 路径成功解析；真实 WebView 运行仍属于 Task 17 门禁。
