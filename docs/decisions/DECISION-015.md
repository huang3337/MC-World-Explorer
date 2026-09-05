# DECISION-015：WebView 数据固定使用 EXE 便携目录

- **状态**：已确认
- **确认日期**：2026-09-05
- **确认人**：项目开发者
- **适用范围**：V0.7.1，`tauri-rewrite` 的 Windows 启动层

## 背景与选择

Tauri 2 在未指定 WebView 数据目录时会使用 LocalAppData，这违反程序数据只能写入 EXE 便携目录的边界。仅设置显示用路径 DTO、依赖当前工作目录或在验收脚本中临时设置环境变量，都不能修复正式程序。

采用：禁止配置自动创建主窗口；在任何 WebView 创建前验证并准备 `EXE/config/webview/`，随后从原窗口配置显式创建唯一主窗口并设置 `data_directory`。

## 启动与安全边界

- `PortablePaths` 继续只计算路径；目录创建属于 Tauri 应用层，不进入 `mcwe-core`。
- 仅创建缺失的 `config/webview`，不删除、迁移或清理已有内容；拒绝非目录、重解析点及可识别 Minecraft 世界目录重叠。
- Windows 启动期间保留允许 WebView 写入、但拒绝根目录被删除或改名的目录句柄。该保护不声称抵御同权限恶意进程修改 profile 内任意普通文件。
- 在 WebView 创建前拒绝 WebView2 的环境变量或注册表策略覆盖；检测只读，不修改环境或注册表。
- 失败时不回退到 AppData、工作目录或临时目录。Windows GUI 发布版通过固定中文原生弹窗报告启动失败后退出。
- `winreg 0.55.0` 仅用于安全只读注册表访问；`windows-sys 0.61.2` 的 `unsafe` 仅封装 `MessageBoxW`，不进入 `mcwe-core`。

## 明确排除

不修改 Minecraft 存档，不引入通用写入框架，不迁移 NBT/MCA/地图/3D 业务，不更改 CSP、窗口权限或单 EXE 发布目标。本决策不授权构建、启动验收、提交、推送、合并或发布。

## 验证要求

以单元测试覆盖路径派生、首次创建、重复使用、异常对象、世界重叠、重解析点与覆盖快照；全量测试通过后，重新构建和真实 EXE 启动验收仍需分别确认。问题在真实落盘验证前保持活动状态。

## 依据

- [Microsoft WebView2 CreateCoreWebView2EnvironmentWithOptions](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/webview2-idl?view=webview2-1.0.3595.46)
- Tauri 2.11.5 本地锁定源码中的默认 `LocalData` 与窗口创建顺序
