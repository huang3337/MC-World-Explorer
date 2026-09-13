# DECISION-011：V0.7 采用 Tauri、Rust、Vue 和 Three.js 重构 V0.6

- **状态**：已确认
- **确认日期**：2026-08-24
- **确认人**：项目开发者
- **适用范围**：`tauri-rewrite`，V0.7.1 至 V0.7.6

## 背景

Java V0.6 已建立稳定的世界管理、二维地图和有限三维浏览行为。项目希望在保留这些用户体验和只读边界的同时，降低对 JVM、JavaFX 和独立 GLFW 窗口的长期依赖。

## 最终选择

- Tauri 2 负责 Windows 桌面壳、窗口和受控 IPC。
- Rust 负责 Minecraft 只读解析、业务计算和后台任务。
- Vue 3 与 TypeScript 负责界面和前端状态。
- Three.js 与 WebGL 2 负责主窗口内的三维显示。
- Minecraft 领域逻辑位于独立 `mcwe-core` crate，不依赖 Tauri、Vue 或 Three.js。
- Java 稳定版作为行为与性能基线，不逐类、逐行翻译旧代码。

## 不可变边界

- Minecraft 存档始终严格只读，前端不获得任意文件路径访问能力。
- Java V0.6.0 稳定版保留在 `main`；V0.7 仅在 `tauri-rewrite` 实施，完成前不替代稳定版。
- 最终 Windows x64 发行目标是不附带 JVM、Node.js、sidecar 或并列运行时目录的单 EXE，并依赖系统已有的 WebView2 Runtime。

## 原因与影响

该组合把只读解析与计算边界放在 Rust，把单窗口交互与 WebGL 显示放在 WebView，避免长期维护 JavaFX 与 GLFW 双窗口协调。代价是 V0.6 能力必须逐项重实现并与 Java 基线对照验证。
