# 代码审查：src/services/backend.ts

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 12 类型化前端服务边界
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-BACKEND-001：服务层信任世界摘要和取消响应且不预检业务参数

- **严重程度**：🟡 中
- **类别**：IPC 正确性
- **文件**：`src/services/backend.ts`、`types.ts`、`backend.test.ts`
- **状态**：已修复

**问题描述**：
初版直接断言 `select_world` 返回 `WorldSummary`、忽略取消 JSON，并允许非安全 session ID、非整数选区及额外路径字段进入 invoke；`dataVersion` 还被错误建模为可缺省而非 Rust 实际返回的 `null`。

**问题分析**：
TypeScript 类型不能验证运行时 IPC。未验证的小型 DTO 会把协议错误带入状态机，未预检参数则弱化 Task 11 建立的严格 Command 边界。

**建议修改**：
验证小型响应的精确字段和数值范围；取消返回布尔状态；二维/三维请求在 invoke 前验证安全 session ID 和固定 `chunkRect`，不接受路径字段。

**影响范围**：
前端唯一 IPC 服务层；既有合法调用名称和参数保持不变。

- **解决日期**：2026-09-07。
- **实际修改**：补齐 DTO、输入和 raw 错误映射，并同步 V0.7.2 测试夹具版本。
- **验证证据**：23 项服务测试覆盖精确 invoke、raw 解码、无效输入、取消、过期、会话过期和畸形响应。
