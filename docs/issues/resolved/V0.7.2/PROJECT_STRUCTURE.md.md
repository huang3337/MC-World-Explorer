# 文档审查：PROJECT_STRUCTURE.md

- **审查日期**：2026-09-08
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 13 收口时的当前工程结构一致性
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-PROJECTSTRUCT-001：工程结构仍停留在 V0.7.1 最小脚手架状态

- **严重程度**：🟡 中
- **类别**：文档准确性
- **文件**：`PROJECT_STRUCTURE.md`、`PROJECT_CONTEXT.md`
- **状态**：已修复

**问题描述**：结构文档仍称前端只调用 `app_status`、应用只有一个 Command、`TaskRegistry` 没有执行器、`mcwe-core` 无第三方依赖且尚不解析 Minecraft；文件树也漏掉 V0.7.2 已实现的 protocol、components、composables、业务 Commands、WorldSession、NBT/Region/Chunk/Surface/Mesh 模块和集成测试。

**问题分析**：这些描述与 Task 3–13 的实际代码和已通过测试矛盾，会让 Task 14 以后错误判断模块职责、可复用入口和完成状态。

**实际修改**：按当前工作树更新正式文件树、端到端调用链、核心依赖和职责说明，并同步修正上下文中“核心尚不解析 Minecraft”和竞态待补的过时文字；历史版本快照不改写。

- **解决日期**：2026-09-08。
- **验证证据**：逐项对照 `rg --files`、生产 invoke 入口、Command 注册、Cargo 依赖和 V0.7.2 进度；Markdown 链接与问题编号在最终静态检查中复核。
