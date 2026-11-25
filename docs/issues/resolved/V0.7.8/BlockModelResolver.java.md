# 代码审查：BlockModelResolver.java

- **审查日期**：2026-11-25
- **审查范围**：1.8–1.12.2 旧版 JSON 模型模式对无 `blockstates/*.json` 内置特殊方块的回退解析
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-BMODEL-032：`LEGACY_JSON_MODEL` 模式未对缺失 `blockstates` 的原版流体与空气类方块回退内置几何

- **严重程度**：🟠 高
- **类别**：模块耦合 / 版本协议边界
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/BlockModelResolver.java`
- **行号**：152–163（审查时快照）
- **状态**：已修复

**问题描述**：
1.8–1.12.2 客户端 JAR 中并不存在 `assets/minecraft/blockstates/water.json`、`lava.json`、`air.json`、`barrier.json`、`structure_void.json` 与 `end_portal.json`（该批文件自 1.13 起才加入原版 `blockstates/`）。`BlockModelResolver` 在 `LEGACY_JSON_MODEL` 模式下直接执行 `read(BLOCKSTATE, block, sources)`，导致预扁平化 1.8–1.12.2 存档中的水、熔岩、空气、屏障与末地传送门全部报 `MISSING_RESOURCE` 失败。

**当前代码**（审查时快照）：
```java
if (rootModel == null && archive.baseProtocolMode() == ClientResourceArchive.ProtocolMode.LEGACY_JSON_MODEL) {
    var mapped = LegacyJsonStateMapper.map(state);
    if (mapped == null) throw fail(Reason.INVALID_MODEL, "非法方块资源 ID：" + state.name());
    block = mapped.blockstateId();
    effectiveState = mapped.state();
} else {
    block = id(state.name());
}
...
root = read(ResourceLocation.Kind.BLOCKSTATE, block, sources);
```

**问题分析**：
1.13+ 客户端通过空 `elements` 的 `blockstates/water.json` → `models/block/water.json` 间接触发 `BuiltinBlockModels`；而 1.8–1.12.2 原版客户端直接由内置流体与特殊渲染器处理，归档内无对应 `blockstates` JSON。

**建议修改**：
在 `LEGACY_JSON_MODEL` 模式下，若目标为上述原版无 `blockstates` 特殊方块且归档内不存在对应 `BLOCKSTATE` JSON，直接调用 `BuiltinBlockModels.resolve(effectiveState)` 生成静态部件并登记纹理来源。

**影响范围**：
1.8–1.12.2 存档三维场景中的水、熔岩、空气、屏障、结构空位与末地传送门解析。

- **解决日期**：2026-11-25
- **实际修改**：在 `BlockModelResolver.resolve` 的 `LEGACY_JSON_MODEL` 分支补入缺失 `BLOCKSTATE` 时的内置特殊几何回退与纹理来源记录。
- **验证证据**：`PreFlatteningResourcePipelineTest.opensLegacy1122ArchiveMapsVanillaStatesAndParsesForgeBlockstateV1` 新增 `minecraft:water` 解析断言并通过全量回归。
