# 代码审查：MapTileCache.java

- **审查日期**：2026-11-24
- **审查工具**：Gemini
- **审查范围**：二维磁盘 PNG 瓦片缓存快照与来源变更校验
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-MAPTILECACHE-001：磁盘瓦片快照未包含 `level.dat` 指纹

- **严重程度**：🟠 高
- **类别**：安全性 / 模块耦合（缓存一致性）
- **文件**：`src/main/java/com/mcworldexplorer/map/MapTileCache.java`
- **行号**：19–23（审查时快照）
- **状态**：已修复

**问题描述**：
二维摘要缓存与三维 `RegionSourceSignature` 已监控 `level.dat`，但磁盘瓦片缓存 `MapTileCache.snapshot` 仅校验 `generation` 与 `.mca` 头签名，未纳入 `level.dat` 文件指纹；同时二维与三维 `RENDER_VERSION` 未随 0.7.6 解码规则更新递增。

**当前代码**（审查时快照）：
```java
static Snapshot snapshot(WorldInfo world, WorldDimension dimension, MapTileKey key) throws IOException {
    validate(world, dimension, key);
    return new Snapshot(world.getFolderPath().toRealPath() + "/" + MapCacheStorage.generation(world),
            sourceStates(dimension, key.bounds()));
}
```

**问题分析**：
预扁平化 Forge 存档的方块数字 ID 映射位于 `level.dat`。若 `level.dat` 中的映射表更新而对应 `.mca` 头未变，`MapTileCache.findReusable` 会在进入 `MapTileGenerator` 前直接返回旧 PNG 瓦片。

**建议修改**：
在 `Snapshot.identity` 中加入 `/ld=` + `MapTileGenerator.levelDatStamp(realWorld)`，并同步递增 `MapViewerController.RENDER_VERSION` 与 `VoxelPreviewCoordinator.RENDER_VERSION`。

**影响范围**：
二维磁盘瓦片缓存复用、提交前后一致性核对及跨版本渲染缓存隔离。

- **解决日期**：2026-11-24
- **实际修改**：`MapTileCache.snapshot` 拼入 `levelDatStamp`，二维与三维 `RENDER_VERSION` 分别更新为 `v076-p1` 与 `v076-model-protocol-1`。
- **验证证据**：新增 `MapTileCacheTest.invalidatesWhenLevelDatChanges` 并通过全量 Gradle 回归。
