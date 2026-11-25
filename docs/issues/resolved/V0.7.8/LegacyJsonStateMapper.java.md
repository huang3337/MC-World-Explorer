# 代码审查：LegacyJsonStateMapper.java

- **审查日期**：2026-11-25
- **审查范围**：1.8–1.12.2 原版 `blockstates/*.json` 文件名与变体属性映射完整性
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-LEGACYMAP-001：部分 1.8–1.12.2 原版石材、双层植物、树苗、灰化土与海绵状态映射缺失

- **严重程度**：🟠 高
- **类别**：代码质量 / 版本协议边界
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/LegacyJsonStateMapper.java`
- **行号**：59–136（审查时快照）
- **状态**：已修复

**问题描述**：
`VanillaLegacyBlocks` 归一后的部分扁平化名称与 1.8–1.12.2 客户端 `assets/minecraft/blockstates/` 实际文件名或必填变体键不一致，包括：
1. `polished_granite` / `polished_diorite` / `polished_andesite` 与 `cut_sandstone` / `cut_red_sandstone`（1.8–1.12.2 为 `smooth_*`）；
2. 双层植物 `tall_grass`、`large_fern`、`lilac`、`rose_bush`、`peony`、`sunflower`（1.8–1.12.2 为 `double_grass`、`double_fern`、`syringa`、`double_rose`、`paeonia`、`sunflower` 且需 `half=lower|upper`，其中 `tall_grass` 未映射还会误命中单格矮草 `tall_grass.json`）；
3. `*_sapling` 缺少 `stage=0|1`，`podzol` 与 `mycelium` 缺少 `snowy=false`，`sponge` / `wet_sponge` 未归一到 `sponge[wet=...]`，`tnt` 缺少 `explode`，`azure_bluet` 未映射至 `houstonia`。

**当前代码**（审查时快照）：
```java
if (path.endsWith("_leaves")) {
    return simple(path, original, props);
}
...
case "grass_block" -> {
    props.put("snowy", "false");
    yield mapped("grass", original, props);
}
```

**问题分析**：
1.8–1.12.2 原版 `BlockModelShapes` 为上述方块生成的 JSON 变体键固定，缺失重命名或属性会导致常见自然地形（磨制花岗岩、高草丛、树苗、灰化土、菌丝、海绵等）报 `MISSING_RESOURCE` 或 `NO_VARIANT`。

**建议修改**：
在 `LegacyJsonStateMapper` 中补齐上述 1.8–1.12.2 原版 `blockstates` 名称与 `stage`、`half`、`snowy`、`wet`、`explode` 属性映射。

**影响范围**：
1.8–1.12.2 存档原版地形与植被方块的三维模型选择。

- **解决日期**：2026-11-25
- **实际修改**：补齐磨制石材、平滑砂岩、6 种双层植物、树苗、灰化土/菌丝、海绵、TNT 与茜草花的 1.8–1.12.2 状态映射。
- **验证证据**：`PreFlatteningResourcePipelineTest` 新增 `polished_granite`、`tall_grass[data=10]` 与 `oak_sapling[data=8]` 解析断言并通过回归。
