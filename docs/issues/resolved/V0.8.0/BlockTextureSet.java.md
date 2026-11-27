# 代码审查：BlockTextureSet.java

- **审查日期**：2026-11-26
- **审查工具**：Gemini
- **审查范围**：共面双层遮罩合并键、复合模型渲染层判定与颜色未解析诊断等级
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-TEXTSET-010：跨 `render_type` 共面双层部件未能合并导致深度冲突，且非未绑定类的颜色解析失败被降为 `INFO`

- **严重程度**：🟠 高
- **类别**：模块耦合 / 代码质量
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/BlockTextureSet.java`
- **行号**：75、381–441、577–590（审查时快照）
- **状态**：已修复

**问题描述**：
1. `SurfaceKey` 包含 `String renderType`，当 `forge:composite` / `neoforge:composite` 等多部件模型在同一几何平面上分别声明 `minecraft:solid` 底层与 `minecraft:cutout`（或 `cutout_mipped`）染色遮罩层时，两层无法合并为单面双层遮罩，而是分别进入 `OPAQUE` 与 `CUTOUT` 批次；在默认 `GL_LESS` 深度测试下，后绘制的共面 `CUTOUT` 层因深度相等被丢弃或发生深度闪烁。
2. 诊断归类中仅 `note.equals(unboundNote)` 被标记为 `Severity.DEGRADED`，其余以 `"颜色未解析："` 开头的降级说明（如缺失群系 JSON、未知草色修饰器、位置调色板超出预算）被归为 `Severity.INFO`（`MODEL_NOTE`）。

**当前代码**（审查时快照）：
```java
private record SurfaceKey(List<Float> positions, List<Float> normal, boolean shaded, String renderType) { }
...
for(String note:stateNotes)stateDiagnostics.add(BlockResourceDiagnostic.capture(state,
    note.equals(unboundNote)?BlockResourceDiagnostic.Severity.DEGRADED:BlockResourceDiagnostic.Severity.INFO,
    note.equals(unboundNote)?"UNBOUND_TINT":"MODEL_NOTE",note,archive,result));
```

**问题分析**：
1. 共面双层合成的不透明度应分别按底层自身的 `renderType` 与顶层自身的 `overlayRenderType` 求得单层 `RenderLayer` 后再按 Porter-Duff `source-over` 合并：只要任一层为 `OPAQUE`（`alpha == 1.0`），合成片元不透明度恒为 `1.0`，合并后仍为 `OPAQUE` 且无共面深度冲突。
2. 所有 `"颜色未解析："` 条目均代表实际颜色降级，应在结构化诊断中标记为 `Severity.DEGRADED`（`TINT_UNRESOLVED`），与真正缺失处理器的 `UNBOUND_TINT` 明确区分。

**建议修改**：
将 `SurfaceKey` 改为 `(positions, normal, shaded, fluid)`，在 `Painted` 中保留 `overlayRenderType` 并由 `singleMaterial` 分别计算底面与遮罩层材质后合并；将非 `unboundNote` 的 `"颜色未解析："` 说明标记为 `Severity.DEGRADED` 与原因码 `"TINT_UNRESOLVED"`。

**影响范围**：
Forge/NeoForge 组合模型共面双层遮罩合成、完整方块邻面遮挡识别及「解析诊断」面板降级分类。

- **解决日期**：2026-11-26
- **实际修改**：调整 `SurfaceKey` 与 `Painted.overlayRenderType`，新增 `singleMaterial` 分层判定合并，并将 `"颜色未解析："` 统一映射为 `Severity.DEGRADED`（`UNBOUND_TINT` / `TINT_UNRESOLVED`）。
- **验证证据**：`VertexBiomeTintAtlasScaleTest`、`FaceMaterialTest`、`UnboundModelTintTest` 与 `BlockResourceDiagnosticTest` 全部通过。
