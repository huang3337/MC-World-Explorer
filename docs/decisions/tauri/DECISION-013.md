# DECISION-013：WebView 数据固定使用 EXE 便携目录

- **状态**：已确认
- **确认日期**：2026-09-05
- **确认人**：项目开发者
- **适用范围**：`tauri-rewrite` 的 Windows 启动层和 WebView2 数据

## 背景

WebView2 默认数据目录位于 LocalAppData，与项目的便携存储边界冲突。

## 最终选择

- 主窗口必须在设置 `EXE/config/webview/` 为 WebView2 数据目录后才能创建。
- 该目录由 Tauri 应用层准备，`mcwe-core` 不承担任何 WebView 写入。
- 拒绝非目录、重解析点、与 Minecraft 世界重叠以及会覆盖便携路径的 WebView2 环境或策略。
- 准备失败时拒绝启动，不回退到 AppData、当前工作目录或临时目录。

## 影响

所有 WebView 自身写入都被限定在便携目录。该决策不授权通用文件系统写入，也不改变 Minecraft 存档只读边界。
