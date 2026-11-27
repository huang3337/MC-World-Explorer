# 代码审查：BlockTintPreparation.java

- **审查日期**：2026-11-26
- **审查工具**：Gemini
- **审查范围**：位置群系颜色调色板稀疏索引、动态染色索引登记与失败说明去重
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-TINTPREP-002：仅出现在邻区边界或缺失群系输入的已绑定方块误报 `UNBOUND_TINT` 且失败说明逐格重复创建

- **严重程度**：🟠 高
- **类别**：错误处理 / 模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/BlockTintPreparation.java`
- **行号**：85–138（审查时快照）
- **状态**：已修复

**问题描述**：
`BlockTintPreparation.prepare` 仅在扫描 `area.target()` 区块且 `colors.colors(...)` 返回非空颜色值后才将索引写入 `resolvedDynamicIndices`，导致已绑定颜色处理器但在目标区内缺失群系/种子输入、或仅出现在 1 格邻区边界的方块在 `BlockTextureSet` 中被误判为 `UNBOUND_TINT`（“未绑定颜色处理器”）；同时 `tint.values().isEmpty()` 分支未经过 `recordedDetails` 去重，在大片缺失输入区域内逐方块重复分配属性映射。

**当前代码**（审查时快照）：
```java
if (colors.needsPosition(entry.getKey(), entry.getValue().model().orElseThrow())) {
    contextual.add(entry.getKey().name());
    continue;
}
...
var tint = colors.colors(state, original.model().orElseThrow(), context);
if (tint.values().isEmpty()) {
    note(source, state, tint.detail());
    continue;
}
```

**问题分析**：
1. “处理器是否绑定”由客户端颜色注册表或插件 `tintLayers` 静态决定，与具体世界坐标是否缺失种子/群系定义无关。若将缺失群系输入的草方块同时追加 `UNBOUND_TINT`，会在诊断面板产生“未绑定颜色处理器”的误导性结论。
2. `BlockTextureSet.loadArea` 为邻面遮挡收集了 `area.target().expanded(1)` 边界方块；若某染色方块仅位于邻区 1 格边界，此前不会被登记动态染色索引，导致 `prepared.diagnostics(state)` 误报 `UNBOUND_TINT`。
3. 同一模型若同时包含静态常量染色层与群系动态染色层，提前 `continue` 会跳过静态染色层的预应用。
4. `tint.values().isEmpty()` 与预算溢出提示未经过 `recordedDetails` 去重，会在数千个同类方块位置重复构造 `TreeMap` 与 `BlockState`。

**建议修改**：
在 `resolved.entrySet()` 阶段通过 `BlockTintResolver.dynamicBoundIndices` 预先登记所有已绑定的位置依赖索引并先行应用同模型上的静态常量/状态颜色；在区块扫描阶段统一通过 `recordedDetails` 对失败说明与预算溢出说明做单状态去重。

**影响范围**：
包含群系/流体着色方块的选区颜色准备、邻区边界状态诊断及缺失种子/群系输入时的降级原因报告。

- **解决日期**：2026-11-26
- **实际修改**：在 `BlockTintResolver` 新增 `dynamicBoundIndices` 与 `staticColors`，在 `BlockTintPreparation.prepare` 状态遍历阶段即登记已绑定的动态索引并应用静态层颜色，同时将 `tint.detail()` 与预算溢出说明统一纳入 `recordedDetails` 去重。
- **验证证据**：`VertexBiomeTintAtlasScaleTest.neighborOnlyContextualBlocksAndMissingBiomesDoNotEmitFalseUnboundTintAndCompositeSolidCutoutMerges` 与全量离线回归通过。
