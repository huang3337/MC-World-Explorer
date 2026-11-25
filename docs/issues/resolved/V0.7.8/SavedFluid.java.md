# 代码审查：SavedFluid.java

- **审查日期**：2026-11-25
- **审查范围**：预扁平化水与熔岩附加值 `data` 的液面高度还原
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-SAVEDFLUID-001：预扁平化流动水与熔岩未从 `data` 属性读取液面高度

- **严重程度**：🟠 高
- **类别**：代码质量 / 版本协议边界
- **文件**：`src/main/java/com/mcworldexplorer/voxel/data/SavedFluid.java`
- **行号**：22–30（审查时快照）
- **状态**：已修复

**问题描述**：
`SavedFluid.ownHeight` 仅读取 `state.properties().get("level")`。预扁平化（1.2.1–1.12.2）区块解码后保存的是 `data`（`0..15`）而非 `level`，导致所有流动水与流动熔岩（`data = 1..7`）均被当作满高度源方块（`8/9`），丢失坡面高度差与流向纹理切换。

**当前代码**（审查时快照）：
```java
String level = state.properties().get("level");
int amount = 8;
if (level != null && level.length() == 1 && level.charAt(0) >= '1' && level.charAt(0) <= '7')
    amount -= level.charAt(0) - '0';
return amount / 9f;
```

**问题分析**：
Minecraft 预扁平化流体的 4 位 `data` 与扁平化后的 `level` 数值定义一致：`0` 为源方块，`1..7` 为逐级递减的水平流动液量，`8..15` 为下落流体（保持顶面满高）。

**建议修改**：
当 `level` 为空时回退读取 `state.properties().get("data")`。

**影响范围**：
1.6.1–1.12.2 存档三维网格中的水与熔岩液面高度及流向重构。

- **解决日期**：2026-11-25
- **实际修改**：在 `SavedFluid.ownHeight` 中增加 `level == null` 时对 `data` 属性的回退读取。
- **验证证据**：`PreFlatteningResourcePipelineTest` 新增 `minecraft:water[data=3]` 液面高度 `5/9` 断言并通过全量回归。
