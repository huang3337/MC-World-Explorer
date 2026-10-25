# DECISION-011：采用 Tauri、Rust、Vue 和 Three.js 进行技术实验

- **状态**：已确认
- **确认日期**：2026-08-24
- **定位修订日期**：2026-10-25
- **确认人**：项目开发者
- **适用范围**：`tauri-rewrite`，T1 至 T6

## 背景

Java V0.6 已建立稳定的世界管理、二维地图和有限三维浏览行为。Tauri 分支用于探索另一种实现，现仅作为技术实验保留；后续正式开发回到 Java，Tauri 不再占用正式版本序列。

## 最终选择

- Tauri 2 负责 Windows 桌面壳、窗口和受控 IPC。
- Rust 负责 Minecraft 只读解析、业务计算和后台任务。
- Vue 3 与 TypeScript 负责界面和前端状态。
- Three.js 与 WebGL 2 负责主窗口内的三维显示。
- Minecraft 领域逻辑位于独立 `mcwe-core` crate，不依赖 Tauri、Vue 或 Three.js。
- Java 稳定版作为行为与性能基线，不逐类、逐行翻译旧代码。

## 不可变边界

- Minecraft 存档始终严格只读，前端不获得任意文件路径访问能力。
- Java 正式开发在 `main` 从 V0.6.0 接续；Tauri 实验仅在 `tauri-rewrite` 保留，源码采用独立版本 `0.1.0`，不替代 Java 主线。
- 最终 Windows x64 发行目标是不附带 JVM、Node.js、sidecar 或并列运行时目录的单 EXE，并依赖系统已有的 WebView2 Runtime。

## 原因与影响

该组合以 Rust 承载只读解析与计算，以 WebView 承载界面与 WebGL 渲染，用于评估单窗口实现的可行性。Java V0.6 继续作为实验行为基线；未完成的对等目标保留为暂停计划，不作为 Java 后续开发的前置条件。
