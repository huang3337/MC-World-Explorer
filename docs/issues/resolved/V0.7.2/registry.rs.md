# 代码审查：tasks/registry.rs

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 10 任务与会话联合接纳
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-TASKREG-001：结果接纳只核对任务令牌而未绑定 session ID

- **严重程度**：🟡 中
- **类别**：正确性
- **文件**：`src-tauri/src/tasks/registry.rs`
- **状态**：已修复

**问题描述**：
初版 `TaskHandle` 只有任务 ID 和原子状态，`accept` 不接收也不核对世界 session ID。

**问题分析**：
任务令牌和世界会话属于两个独立代次；执行器应在生产结果边界同时证明二者与提交请求一致。

**建议修改**：
在句柄创建时冻结 session ID，接纳时同时核对登记器当前令牌、句柄 session 和调用方 session。

**影响范围**：
世界切换期间二维/三维结果的后端接纳。

- **解决日期**：2026-09-07。
- **实际修改**：`begin(session_id)` 和 `accept(handle, session_id, value)` 建立联合身份，并增加 pending discard 终态。
- **验证证据**：同编号外来令牌、错误 session、取消/接纳竞争和 ID 耗尽测试通过。
