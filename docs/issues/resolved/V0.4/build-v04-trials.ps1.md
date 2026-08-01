# 代码审查：build-v04-trials.ps1

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：V0.4 非发布 app-image 构建和本地库校验
- **问题总数**：1 个（严重 0 / 高 0 / 中 0 / 低 1）

### ISSUE-V04PACKAGE-001：核心 LWJGL native 通配符可误匹配子模块 native

- **严重程度**：低
- **类别**：代码质量
- **文件**：`packaging/build-v04-trials.ps1`
- **行号**：审查时 nativePattern 列表
- **状态**：已修复

**问题描述**：
lwjgl-*-natives-windows.jar 也能匹配 lwjgl-glfw 与 lwjgl-opengl 文件，无法单独证明 core native 存在。

**当前代码（审查时快照）**：

```powershell
"lwjgl-*-natives-windows.jar"
```

**问题分析**：
依赖通常完整，但缺 core native 时预检查可能错误通过，直到运行时失败。

**建议修改**：
枚举输入 JAR 后用明确正则分别确认 core、glfw 和 opengl 的 Windows native。

**影响范围**：
便携映像的构建前依赖诊断。

- **解决日期**：2026-08-01
- **实际修改**：枚举全部 JAR 名称，并用三个锚定的 .NET 正则分别校验 core、glfw 和 opengl native；避免 PowerShell `-Filter` 字符类兼容问题。
- **验证证据**：build-v04-trials.ps1 成功生成两套映像，副本离开 Gradle 缓存后均可启动。
