# 代码审查：build.gradle

- **审查日期**：2026-11-24
- **审查工具**：Gemini
- **审查范围**：构建版本号与隔离输出目录配置
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-BUILD-004：0.7.6 工作区构建版本与输出目录未递增

- **严重程度**：🟠 高
- **类别**：代码质量（构建一致性）
- **文件**：`build.gradle`
- **行号**：8, 130（审查时快照）
- **状态**：已修复

**问题描述**：
预扁平化兼容实现完成后，`build.gradle` 中的 `version` 与 `layout.buildDirectory` 仍保留为 `0.7.5` 与 `build/core-0.7.5`。

**当前代码**（审查时快照）：
```groovy
version = '0.7.5'
...
layout.buildDirectory = layout.projectDirectory.dir('build/core-0.7.5')
```

**问题分析**：
关于对话框版本展示、插件宿主兼容校验 `mcwe-build.properties` 以及构建隔离目录均由 `build.gradle` 生成，版本未递增会导致运行态仍自报 `0.7.5`。

**建议修改**：
将 `version` 更新为 `'0.7.6'`，`layout.buildDirectory` 更新为 `'build/core-0.7.6'`。

**影响范围**：
应用构建元数据、关于窗口版本展示、插件宿主版本核对与编译输出隔离。

- **解决日期**：2026-11-24
- **实际修改**：已更新为 `0.7.6` 与 `build/core-0.7.6`。
- **验证证据**：`generateBuildMetadata`、`jar`、`test` 与 `verifyCoreIsolation` 通过，主 JAR 内 `mcwe-build.properties` 核实为 `version=0.7.6`。
