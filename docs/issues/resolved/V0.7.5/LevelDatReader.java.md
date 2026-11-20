# LevelDatReader.java 审查记录

2026-11-18 使用 Codex 检查 Java 主线工作区从世界元数据到二维地图中心的调用路径。共确认 1 项高严重程度问题。

## ISSUE-LEVELDAT-007 结构化世界出生点未进入二维预览

| 项目 | 记录 |
|---|---|
| 严重程度、类别 | 高，跨版本解析与界面正确性 |
| 文件、行号 | [LevelDatReader.java](../../../../src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java) 第 87–94 行 |
| 状态 | 0.7.5 工作区已修复并回归 |
| 影响范围 | 二维首屏中心、世界出生点标记，以及基于初始位置的选区操作 |

**问题描述与复现**  
在 26.3 真实主世界中，`level.dat` 保存了 `Data.spawn.dimension = minecraft:overworld` 和 `Data.spawn.pos = [0, 63, 112]`。读取后世界出生点标志为假，`PreviewCenterResolver` 返回 `ORIGIN_FALLBACK`，二维中心落在 X/Z (0, 0)。实读原点区块的 256 列地表全是水，出生点区块 (0, 7) 的 256 列含水、石头、沙等 7 种方块。

**审查时的代码**

```java
if (data.keySet().contains("SpawnX")
        && data.keySet().contains("SpawnY")
        && data.keySet().contains("SpawnZ")) {
    info.setSpawnPosition(
            data.getInt("SpawnX"),
            data.getInt("SpawnY"),
            data.getInt("SpawnZ"));
}
```

**原因与建议**  
读取器只查旧版三个整数字段。建议在同一次元数据读取中识别结构化出生点并校验位置类型、长度和维度。保留旧字段支持，按出生点实际维度传给视口和标记。玩家重生点、世界出生点与原点的既有优先顺序应在归一化后继续生效。

**验收依据**  
用非零坐标检查中心来源和标记位置，覆盖旧字段、新结构、非主世界、错误数据与确实缺失。比较同一存档修复前后的首屏 Region 请求，确认无需额外扫描地图。

**2026-11-19 验证**  
已在 0.7.5 工作区修复。新旧字段共用坐标与维度读取器。在 26.3 的同一个存档中，二维入口现返回 WORLD_SPAWN (0, 112)，命中区块 (0, 7)，256 个地表列共 7 种方块。自动回归覆盖跨维度、结构缺维度、数组长度错误与旧式出生点。地图中心和出生点标记都按维度选取。

## ISSUE-LEVELDAT-008 玩家位置读取器重复解码维度标签

2026-11-19 · 低 · 代码质量 · 已修复。

LevelDatReader 原第 133 行与 [PlayerDataReader.java](../../../../src/main/java/com/mcworldexplorer/nbt/PlayerDataReader.java) 原第 235 行各自维护 `readDimension(BinaryTag)`。两个位置入口对字符串、旧版数值、缺失值和类型错误有相同约定。现统一由 `SavedSpawn.readDimension` 读取，结构化出生点仍单独要求有效字符串维度，旧式出生点继续拒绝空白维度。世界与玩家读取回归通过，覆盖数值维度、重生点与不完整位置。
