# 代码审查：package.json

- **审查日期**：2026-09-04
- **审查工具**：Codex
- **审查范围**：Task 10 前端类型检查门禁
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-PKGJSON-001：构建命令未检查引用的应用配置

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`package.json`
- **行号**：审查时第 8 行
- **状态**：已修复

**问题描述**：根 tsconfig 的 files 为空，原命令没有 build 模式，也未显式指定应用配置，不能作为源码类型检查门禁。

**当前代码**（审查时快照）：
```json
"build": "vue-tsc --noEmit && vite build"
```

**问题分析**：同一源码下 `vue-tsc --noEmit` 返回 0；`vue-tsc --noEmit -p tsconfig.app.json` 则正确报告尚未实现的 backend 模块与尚未安装的测试类型。此结论仅针对本次源码，不反推历史验收结果。

**建议修改**：显式检查应用与工具配置，并让 build 调用该检查。

**影响范围**：前端源码及测试的类型错误检测，不改变生产运行行为。

**解决日期**：2026-09-04。

**实际修改**：增加 typecheck 脚本，分别执行 `vue-tsc --noEmit -p tsconfig.app.json` 与 `tsc --noEmit -p tsconfig.node.json`；build 先运行 typecheck 再构建。工具配置纳入 vitest.config.ts。

**验证证据**：显式配置检查在服务尚未实现时正确报告缺失模块；实现后 `npm run build` 同时通过两项类型检查及 Vite 构建，14 项前端测试通过。历史带日期的构建记录不改写。
