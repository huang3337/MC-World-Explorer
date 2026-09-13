# 代码审查：commands/mod.rs

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 11 业务 Command IPC 边界
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-COMMANDS-001：业务 Command 静默接受未声明的 JSON 参数

- **严重程度**：🟡 中
- **类别**：IPC 安全
- **文件**：`src-tauri/src/commands/mod.rs`、`app_status.rs`、`load_surface.rs`、`build_mesh.rs`、`cancel_task.rs`
- **状态**：已修复

**问题描述**：
Tauri 默认忽略没有映射到函数参数或 DTO 字段的 JSON 字段。初版状态、二维、三维和取消 Command 因而会静默接受 `path`、`taskId` 等未声明输入；三维选区内部也会忽略额外字段。

**问题分析**：
这些字段不会直接获得文件能力，但静默接受会模糊“路径只来自 Rust dialog、后续读取只由 session ID 授权”的信任边界，并违反冻结计划要求的严格参数契约。

**建议修改**：
在共享 Command 入口按每个 Command 的固定键集合校验原始 JSON object；无参数 Command 只接受空 object，三维选区同时拒绝嵌套额外字段和 raw body。

**影响范围**：
仅收紧未声明 IPC 输入；既有前端的无参数调用、`sessionId` 和固定 `chunkRect` 请求形状保持不变。

- **解决日期**：2026-09-07。
- **实际修改**：新增统一严格参数检查，覆盖全部已注册 Command；`ChunkRectDto` 同时启用 `deny_unknown_fields`。
- **验证证据**：Tauri mock IPC 已证明伪造前端路径、任务 ID、外层和嵌套额外字段及 raw 请求被拒绝；正常 JSON/raw 响应仍通过。
