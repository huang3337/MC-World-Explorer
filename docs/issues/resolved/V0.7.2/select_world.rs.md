# 代码审查：select_world.rs

- **审查日期**：2026-09-09
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 9 与 Task 16 专用目录选择、后台校验和会话切换边界
- **问题总数**：3 个（🔴 0 / 🟠 2 / 🟡 1 / 🟢 0）

### ISSUE-SELECTWORLD-001：无参数 Command 静默接受额外路径字段

- **严重程度**：🟡 中
- **类别**：IPC 安全
- **文件**：`src-tauri/src/commands/select_world.rs`、`src-tauri/src/error.rs`
- **状态**：已修复

**问题描述**：
Tauri 默认忽略未映射 JSON 字段，初版 `select_world` 会静默接受前端提交的 `path`、`displayPath` 等额外参数。

**问题分析**：
实际读取路径仍来自 Rust dialog，但静默接受会模糊信任边界，使调用方误以为显示路径可以驱动读取，也违反冻结计划的严格参数要求。

**建议修改**：
读取原始 IPC request，要求 body 必须是空 JSON object；其他 JSON 字段和 raw body 使用稳定参数错误拒绝。

**影响范围**：
`select_world` IPC 请求形状；正常 `invoke("select_world")` 不变。

- **解决日期**：2026-09-07。
- **实际修改**：新增空参数检查和 `INVALID_ARGUMENTS` 错误码，验证在打开 dialog 前完成。
- **验证证据**：伪造 `path/displayPath`、非 main 窗口和远程来源 mock IPC 测试通过；系统对话框取消不触碰现有任务。

### ISSUE-SELECTWORLD-002：候选世界文件校验同步占用 IPC 路径

- **严重程度**：🟠 高
- **类别**：线程 / 响应性
- **文件**：`src-tauri/src/commands/select_world.rs`、`src-tauri/src/world_session.rs`
- **状态**：已修复

**问题描述**：目录对话框返回后，原实现直接在 async Command 中读取、解压并解析 `level.dat`，再验证 `region/`，没有进入 DECISION-022 的重型工作边界。

**实际修改**：候选校验改为提交到现有单 worker、单 pending 执行器，使用内部预会话作用域并传递协作取消令牌；IPC 只异步等待 receipt。

### ISSUE-SELECTWORLD-003：会话先替换、旧业务任务后取消

- **严重程度**：🟠 高
- **类别**：会话原子性 / 竞态
- **文件**：`src-tauri/src/commands/select_world.rs`、`src-tauri/src/world_session.rs`
- **状态**：已修复

**问题描述**：原实现先提交候选会话，再取消旧任务，与 DECISION-024 的顺序相反；候选失败也不会落实开发者确认的“新选择即取消旧后台工作”语义。

**实际修改**：选择路径后先取消当前任务，再执行候选校验；失败保留旧会话但不恢复旧任务，成功前再次取消竞态任务并原子提交。该重大顺序由 DECISION-026 固定。

- **解决日期**：2026-09-09。
- **验证证据**：目录取消保留活动任务、无效候选取消活动任务、候选失败保留旧会话、成功替换和旧 ID 拒绝测试通过。
