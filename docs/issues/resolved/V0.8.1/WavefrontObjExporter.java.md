# 代码审查：WavefrontObjExporter.java

- **审查日期**：2026-11-27
- **审查工具**：Gemini
- **审查范围**：双层遮罩高分辨率/旋转 UV 烘焙保真度、图集容量与导出异常/取消时的临时文件清理
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-OBJEXP-001：双层遮罩烘焙未保留高分辨率遮罩尺寸与旋转 UV 映射，且中途取消或失败会遗留残缺输出文件

- **严重程度**：🟠 高
- **类别**：代码质量 / 资源管理
- **文件**：`sdk/src/main/java/com/mcworldexplorer/sdk/WavefrontObjExporter.java`
- **行号**：36–37、210–224、489–505（审查时快照）
- **状态**：已修复

**问题描述**：
1. `ExportAtlasBuilder.bakeOrReuse` 固定按底层瓦片宽高 `w × h` 创建烘焙缓冲区，并假设遮罩层 `(ox, oy)` 与底层 `(dx, dy)` 同向对齐：当资源包遮罩层分辨率高于底层或遮罩面相对底层存在 90°/180°/镜像 UV 变换时，会降低遮罩分辨率或产生方向错位。
2. `export` 直接向目标 `.obj`、`.mtl`、`_atlas.png` 写入且无 `CancellationSignal` 重载，若磁盘写入异常或中途取消，目标目录会残留不完整的导出文件。

**当前代码**（审查时快照）：
```java
int[] baked = new int[w * h];
int ow = key.hasOverlay ? (key.ox1 - key.ox0) : 0;
int oh = key.hasOverlay ? (key.oy1 - key.oy0) : 0;
for (int dy = 0; dy < h; dy++) {
    int sy = key.by0 + dy;
    int oy = key.hasOverlay ? key.oy0 + Math.min(oh - 1, (dy * oh) / h) : 0;
```

**问题分析**：
通过三角形三顶点的局部基础 UV 与遮罩 UV 求解规范化角点 `(0,0)`、`(1,0)`、`(0,1)` 的量化仿射映射（`OverlayMapping`），可使同一旋转四边形的两个三角形共享同一个 `BakedTileKey` 并精确还原旋转/镜像遮罩，同时取 `Math.max(bw, ow) × Math.max(bh, oh)` 保留完整像素细节；文件写出采用同目录临时文件暂存后原子替换，并在 `finally` 中清理未提交的临时文件。

**建议修改**：
引入 `OverlayMapping` 仿射 UV 映射与 `Math.max` 分辨率保留，将导出图集最大行宽提升至 `4096`，新增带 `CancellationSignal` 的 `export` 重载并通过临时文件暂存保证异常/取消时零残缺文件残留。

**影响范围**：
`WavefrontObjExporter` 纹理烘焙精度、大选区图集容量及文件写出原子性。

- **解决日期**：2026-11-27
- **实际修改**：实现 `OverlayMapping` 与高分辨率保留、扩容 `EXPORT_ATLAS_WIDTH = 4096`，并补齐取消检查与临时文件暂存替换机制。
- **验证证据**：`WavefrontObjExporterTest.preservesHigherResolutionRotatedOverlayAndCleansUpStagingOnCancellation` 验证通过。
