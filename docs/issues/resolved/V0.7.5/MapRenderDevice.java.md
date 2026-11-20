# 代码审查 · MapRenderDevice.java

2026-11-19，Codex 审查 JavaFX 二维设备读数。共 1 项，🟡 中。

### ISSUE-MAPRENDERDEVICE-001 · Gradle 启动时设置页读不到实际设备

- **类别与位置**　启动兼容性，审查时位于 `src/main/java/com/mcworldexplorer/ui/MapRenderDevice.java:13`。2026-11-19 已修复。
- **审查时快照**　`GraphicsPipeline.getPipeline()`、Glass 窗口和 D3D 驱动信息均通过反射访问。读取失败由同一个异常分支处理，页面显示“无法读取当前设备”。
- **影响与复现**　`run-app.bat` 调用 Gradle 的 `run` 任务，JavaFX 21 位于命名模块。该任务缺少诊断所需的模块访问权限。本机 Prism 日志记录 Direct3D 与 NVIDIA GeForce RTX 5060 Laptop GPU，同时设置页的读取方法返回失败。以类路径启动的旧验收环境可读到设备。
- **候选处理**　在 Gradle 客户端启动参数中向应用导出 `com.sun.prism` 与 `com.sun.glass.ui`，开放 `com.sun.prism.d3d` 的反射访问。同一 Gradle 入口实测可分别读回 RTX Direct3D 和 Prism CPU。建议在诊断日志保留失败环节，设置页分别呈现已检测的硬件与当前窗口的实际后端；OpenGL 设备由三维窗口创建后的驱动读数提供。
- **实际处理**　Gradle 的 `run` 和 `run-app.bat` 均配置所需 JavaFX 模块访问。批处理使用专属 `MCWE.exe`。设置页从 Windows 当前连接的显示设备中列出实体 PCI 显卡，显示主窗口实际使用的 Prism 设备，并独立显示最近一次 OpenGL 设备与版本。省电和高性能偏好按本用户及 EXE 路径应用，其他 Windows 图形选项保留。
- **验收**　从原项目的批处理打开设置，可读到 Intel UHD 和 NVIDIA GeForce RTX 5060 Laptop GPU；该次主窗口报告 RTX Direct3D。设置页的保存、取消与程序重启复测见[真实存档对照](../../../progress/V0.7.5.md#gpu-preference)。
