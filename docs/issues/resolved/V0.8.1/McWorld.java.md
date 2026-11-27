# 代码审查：McWorld.java

- **审查日期**：2026-11-27
- **审查工具**：Gemini
- **审查范围**：无头世界会话的维度区域目录真实路径/符号链接校验与 OBJ 导出取消传播
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-MCWORLD-001：`renderMapImage` 未拦截符号链接越界，且 `exportWavefrontObj` 未向下游导出器传递取消信号

- **严重程度**：🟠 高
- **类别**：安全性 / 错误处理
- **文件**：`sdk/src/main/java/com/mcworldexplorer/sdk/McWorld.java`
- **行号**：180–188、200–240（审查时快照）
- **状态**：已修复

**问题描述**：
1. `renderMapImage` 仅通过词法层面的 `toAbsolutePath().normalize().startsWith(directory)` 校验维度 `region` 目录，未检查 `region` 目录或 `.mca` 文件是否为指向存档目录外部的符号链接/重解析点。
2. `exportWavefrontObj(SceneBuildRequest, Path, String)` 在场景构建完成后调用 `WavefrontObjExporter.export` 时未传递 `request.cancellation()`。

**当前代码**（审查时快照）：
```java
Path regionDirectory = targetDimension.regionDirectory().toAbsolutePath().normalize();
if (!regionDirectory.startsWith(directory)) {
    throw new IllegalArgumentException("dimension region directory must stay inside world directory");
}
```

**问题分析**：
当 SDK 用于服务端或批处理转换不可信外部存档压缩包时，纯词法路径归一化无法识别指向外部目录的符号链接；同时大选区导出阶段应继续响应调用方传入的取消信号。

**建议修改**：
在 `renderMapImage` 中当维度 `region` 目录不存在时直接返回空白底色图；存在时校验 `!Files.isSymbolicLink(regionDirectory)` 且 `regionDirectory.toRealPath().startsWith(directory.toRealPath())`，并在遍历 `.mca` 时跳过符号链接文件；在 `exportWavefrontObj` 中将 `request.cancellation()` 传入 `WavefrontObjExporter.export`。

**影响范围**：
无头二维地图渲染的路径安全边界与三维 OBJ 导出的协同取消行为。

- **解决日期**：2026-11-27
- **实际修改**：补齐 `toRealPath()` 与 `Files.isSymbolicLink` 双重防护、缺失 `region` 目录空图回退，并打通 `exportWavefrontObj` 取消信号传递。
- **验证证据**：`WorldExplorerSdkTest` 与全量离线回归通过。
