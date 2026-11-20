# 三维网格资源

## ISSUE-GLMESH-002 透明排序迁移后，每份 GPU 网格仍保存旧的渲染层

2026-11-19 · 低 · 代码质量 · 已修复。

正式渲染器的 [OpenGlMeshResources.java](../../../../src/main/java/com/mcworldexplorer/voxel/render/lwjgl/OpenGlMeshResources.java) 原第 20、115 行保存 `RenderLayer layer` 并提供 `layer()`。场景上传已从 `MeshBatch.layer()` 分出不透明与透明网格，绘制不再读取该副本。移除字段、赋值和访问器，保留网格上传及透明面的跨区块排序。原生 OpenGL 交错透明面、选框与导航回归通过。
