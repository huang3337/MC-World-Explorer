# 代码审查：tasks/executor.rs

- **审查日期**：2026-09-09
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 10 单 worker、pending 替换与结果接纳
- **问题总数**：3 个（🔴 0 / 🟠 1 / 🟡 2 / 🟢 0）

### ISSUE-EXECUTOR-001：互斥 gate 会形成无界 `spawn_blocking` 等待队列

- **严重程度**：🟠 高
- **类别**：资源管理
- **文件**：`src-tauri/src/tasks/mod.rs`、`commands/load_surface.rs`、`commands/build_mesh.rs`
- **状态**：已修复

**问题描述**：
初版每个请求都创建一个 `spawn_blocking` 工作，再在共享 Mutex gate 后等待。gate 虽限制同时执行一个重型区段，但快速请求仍会创建任意数量的阻塞 worker。

**问题分析**：
逻辑任务取消不能移除已经进入运行时阻塞池的等待项，违反 DECISION-022 的“一个 active＋一个最新 pending”有界模型。

**建议修改**：
使用一个应用级专用工作线程和一个可替换 pending 槽；新请求取消 active、淘汰旧 pending，禁止建立通用或无界队列。

**影响范围**：
二维加载、三维网格、任务替换、内存和关闭等待。

- **解决日期**：2026-09-07。
- **实际修改**：新增类型擦除但输出类型安全的 `TaskExecutor`，生产端固定一个重型线程和最多一个 pending job。
- **验证证据**：连续三请求测试证明第二个 pending 被淘汰、第一项过期、第三项运行，峰值重型 worker 精确为 1。

### ISSUE-EXECUTOR-002：过期任务在接纳前编码大 DTO，worker panic 缺少稳定分类

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src-tauri/src/tasks/executor.rs`、`commands/load_surface.rs`、`commands/build_mesh.rs`、`src-tauri/src/error.rs`
- **状态**：已修复

**问题描述**：
初版 worker 在登记器接纳前编码完整二进制 buffer；即使结果已经过期，也可能完成最高 128 MiB 分配。`spawn_blocking` panic 又统一映射为 `IO_ERROR`。

**问题分析**：
领域结果应先通过任务/session 身份接纳，再映射协议 DTO。panic 不是世界 I/O 失败，且不得使后续执行能力失效。

**建议修改**：
执行器接纳类型化领域结果，Command 接纳后再编码；捕获单项 panic，返回固定 worker 错误并继续处理下一项。

**影响范围**：
过期结果资源消耗、错误展示和执行器可恢复性。

- **解决日期**：2026-09-07。
- **实际修改**：执行器通过类型安全 receipt 返回已接纳领域结果；新增 `WORKER_FAILED`/`SHUTTING_DOWN`，单项 panic 被隔离。
- **验证证据**：panic 后下一任务成功、错误码序列化和关闭后拒绝提交测试通过。

### ISSUE-EXECUTOR-003：生产启动与内部 pending 不变量使用 panic

- **严重程度**：🟡 中
- **类别**：错误处理 / 可用性
- **文件**：`src-tauri/src/tasks/executor.rs`、`src-tauri/src/error.rs`
- **状态**：已修复

**问题描述**：工作线程创建失败会由 `Default` 中的 `expect` 直接终止进程，worker loop 对 pending 状态也使用 `expect`，无法形成稳定应用错误。

**实际修改**：执行器新增可失败构造；`Default` 在线程无法创建时形成不可用状态，提交返回 `WorkerUnavailable` 并统一映射为 `WORKER_FAILED`。worker loop 的内部竞态分支改为安全重试，不再 panic。

- **解决日期**：2026-09-09。
- **验证证据**：不可用执行器提交、取消与关闭测试通过，Clippy `-D warnings` 通过。
