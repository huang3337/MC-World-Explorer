# 代码审查：StandardTintBoundaryTest.java

- **审查日期**：2026-11-26
- **审查工具**：Gemini
- **审查范围**：真实客户端与合成群系边界测试对 `0.8.0` 顶点着色契约的同步适配
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-TINTBNDTEST-001：显式启用的颜色边界测试仍断言旧版 `__mcwe_tint` 克隆状态与预染色图集像素

- **严重程度**：🟠 高
- **类别**：代码质量 / 测试契约一致性
- **文件**：`src/test/java/com/mcworldexplorer/voxel/resource/StandardTintBoundaryTest.java`
- **行号**：54–60（审查时快照）
- **状态**：已修复

**问题描述**：
`StandardTintBoundaryTest` 默认随 `MCWE_COLOR_FIXTURE` 环境变量按条件跳过；在 `0.8.0` 移除 `__mcwe_tint` 状态克隆与图集预烘焙染色后，该测试中的 `moreThan4096ColoredPositionsShareVariantsAndNegativeCoordinatesDoNotAlias` 仍断言 `keys.size() == 2`、`assertNotEquals(west, east)` 及图集瓦片已被染成红/蓝像素，在提供真实客户端样本时会直接失败。

**当前代码**（审查时快照）：
```java
assertEquals(2,keys.size(),"8192个目标位置只保留两种颜色模型");
var west=ready.renderState(grass,-10,-8,8);var east=ready.renderState(grass,10,-8,8);assertNotEquals(west,east);
...
assertTile(ready,west,BlockModelResolver.Direction.UP,BlockTextureSet.composite(raw,0x1FF0000,null,-1));
assertTile(ready,east,BlockModelResolver.Direction.UP,BlockTextureSet.composite(raw,0x10000FF,null,-1));
```

**问题分析**：
`0.8.0` 架构下，8,192 个草方块位置共用同一个未克隆的 `minecraft:grass_block[snowy=false]` 模型模板（`keys.size() == 1`），`TextureAtlas` 保存单份未染色 `grass_block_top` 原图，东/西两侧与范围外坐标的颜色差异通过 `BlockTextureSet.surfaceColor(up, x, y, z)` 按稀疏区段调色板精确解析。

**建议修改**：
更新断言为验证 8,192 个位置共用单一未克隆几何状态、图集瓦片等于原始未染色纹理，并核验 `(-10, -8, 8)` 解析为 `0xFF0000`、`(10, -8, 8)` 解析为 `0x0000FF`、范围外 `(100, -8, 8)` 回退为 `0xFFFFFF`。

**影响范围**：
显式启用 `MCWE_COLOR_FIXTURE` 时的真实客户端群系边界与负坐标防混叠回归测试。

- **解决日期**：2026-11-26
- **实际修改**：同步更新 `StandardTintBoundaryTest` 断言至 `0.8.0` 未染色图集与逐坐标 `surfaceColor` 契约。
- **验证证据**：测试编译与全量离线回归通过。
