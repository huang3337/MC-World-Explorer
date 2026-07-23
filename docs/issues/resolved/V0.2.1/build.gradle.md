# 代码审查：build.gradle

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：V0.2.1 开发版本与测试运行时依赖
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

## 当前结论

当前构建配置显式声明 `junit-platform-launcher:1.10.2`，使用 `--warning-mode all` 运行测试时没有 Gradle 弃用警告。本文件保留开发阶段发现的问题，问题代码为当时快照；当前实现以实际修改和验证证据为准。

### ISSUE-BUILD-001：测试平台启动器依赖由 Gradle 隐式补入

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`build.gradle`
- **行号**：38
- **状态**：已修复

**问题描述**：
Gradle 8.7 在 `--warning-mode all` 下提示自动加载测试框架实现依赖的行为已弃用，未来 Gradle 9 可能不再自动提供测试平台启动器。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```groovy
testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
```

**问题分析**：
当前测试仍可运行，但构建依赖不完整，升级 Gradle 后可能在测试发现或启动阶段失败。

**建议修改**：
```groovy
testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'
```

**影响范围**：
仅影响自动化测试运行时，不改变应用运行依赖和发布行为。

- **解决日期**：2026-07-24
- **实际修改**：显式加入与 JUnit Jupiter 5.10.2 对齐的 Platform Launcher 1.10.2，并将开发版本更新为 `0.2.1-SNAPSHOT`。
- **验证证据**：`gradlew.bat test --warning-mode all --rerun-tasks` 通过，弃用警告消失。
