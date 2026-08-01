# 代码审查：JavaFxOrbitController.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：JavaFX 鼠标、滚轮、复位和自动测量相机控制
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-JFXORBIT-001：自动运动期间每帧替换 Affine 产生额外分配

- **严重程度**：中
- **类别**：资源管理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/render/javafx/JavaFxOrbitController.java`
- **行号**：审查时 77
- **状态**：已修复

**问题描述**：
每帧创建新 Affine 并 setAll 替换 Transform 列表。

**当前代码（审查时快照）**：

```java
worldGroup.getTransforms().setAll(JavaFxCameraTransform.viewTransform(state));
```

**问题分析**：
120 秒运动测量中会产生持续短命对象和 JavaFX 列表更新，干扰内存稳定性证据。

**建议修改**：
控制器持有单个 Affine，初始化时加入列表，之后只更新矩阵元素。

**影响范围**：
连续旋转/缩放时的分配和工作集趋势。

- **解决日期**：2026-08-01
- **实际修改**：新增可复用 update 方法，控制器只持有并更新一个 Affine。
- **验证证据**：最终 120 秒四分段稳定性复核中 JavaFX Q3 到 Q4 仅增加约 0.5 MB。
