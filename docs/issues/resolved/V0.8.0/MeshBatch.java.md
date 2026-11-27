# 代码审查：MeshBatch.java

- **审查日期**：2026-11-26
- **审查工具**：Gemini
- **审查范围**：三维网格批次记录类的数组非空校验、遮罩层前置条件校验与顶点下标边界检查
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-V05-BATCH-002：遮罩纹理坐标缺少基础纹理前置校验且空数组模式下顶点查询未检查越界下标

- **严重程度**：🟡 中
- **类别**：安全性 / 边界校验
- **文件**：`src/main/java/com/mcworldexplorer/voxel/mesh/MeshBatch.java`
- **行号**：28–73、113–115（审查时快照）
- **状态**：已修复

**问题描述**：
1. 紧凑构造器未对传入数组执行显式 `Objects.requireNonNull`，且允许在 `textureCoordinates.length == 0`（无基础纹理）时传入非空 `overlayTextureCoordinates`。
2. `vertexColor(int vertex)` 与 `hasOverlay(int vertex)` 在 `vertexRgb` / `overlayRgb` 为空数组时直接返回默认值，未校验 `0 <= vertex < vertexCount()`，与非空数组时的越界异常行为不一致。

**当前代码**（审查时快照）：
```java
public int vertexColor(int vertex) { return vertexRgb.length == 0 ? rgb : vertexRgb[vertex]; }

public boolean hasOverlay(int vertex) { return overlayRgb.length != 0 && overlayRgb[vertex] >= 0; }
```

**问题分析**：
`MeshBatch` 同时承担渲染上传与后续无 UI 核心网格导出的公共数据载体职责。若允许无基础 UV 的批次携带 `overlayUv`，着色器将在 `vTextured == 0` 场景下采样未绑定的图集纹理；下标校验不一致也会掩盖调用方的顶点遍历越界错误。

**建议修改**：
在构造器中补齐数组参数 `Objects.requireNonNull` 及 `overlayTextureCoordinates` 对基础 `textureCoordinates` 非空的前置校验，并在 `vertexColor`、`hasOverlay` 中统一调用 `Objects.checkIndex(vertex, vertexCount())`。

**影响范围**：
`MeshBatch` 构造契约、OpenGL 顶点交错上传及外部网格消费方的边界安全性。

- **解决日期**：2026-11-26
- **实际修改**：补齐数组非空校验、遮罩 UV 前置校验及 `Objects.checkIndex` 顶点范围检查。
- **验证证据**：`OpenGlMeshResourcesTest.meshBatchValidatesNullArraysOverlayPrerequisitesAndVertexBounds` 验证通过。
