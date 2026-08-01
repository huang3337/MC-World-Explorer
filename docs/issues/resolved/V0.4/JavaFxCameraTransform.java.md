# 代码审查：JavaFxCameraTransform.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：共享环绕相机到 JavaFX 坐标的适配
- **问题总数**：1 个（严重 0 / 高 1 / 中 0 / 低 0）

### ISSUE-JFXCAM-001：JavaFX 旋转世界实现与共享 lookAt 相机语义不一致

- **严重程度**：高
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/render/javafx/JavaFxCameraTransform.java`
- **行号**：初版 JavaFxOrbitController 75-79
- **状态**：已修复

**问题描述**：
JavaFX 初版只按 yaw/pitch 旋转已居中的世界，LWJGL 使用共享 CameraPose.lookAt，导致同条件截图朝向和取景不同。

**当前代码（审查时快照）**：

```java
rotationGroup.getTransforms().setAll(
        new Rotate(Math.toDegrees(state.yaw()), Rotate.Y_AXIS),
        new Rotate(-Math.toDegrees(state.pitch()), Rotate.X_AXIS));
```

**问题分析**：
后端比较条件不公平，也无法证明两个后端消费相同相机语义。

**建议修改**：
由 CameraPose 构造 right/down/forward 视图基，JavaFX 和 OpenGL 使用同一眼点与焦点。

**影响范围**：
初始视角、交互方向和性能对比有效性。

- **解决日期**：2026-08-01
- **实际修改**：新增 JavaFxCameraTransform，将眼点映射到原点、焦点映射到正 Z，并设置相同 45 度垂直 FOV。
- **验证证据**：JavaFxCameraTransformTest 通过；两端真实区块截图位置、方向和几何统计一致。
