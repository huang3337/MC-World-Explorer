# 代码审查：WorldDimensionDiscovery.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：原版和 Mod 维度 Region 目录发现
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

## 当前结论

当前发现器在没有 `dimensions/` 时使用可变列表，并在遇到 `region`、`entities` 和 `poi` 时跳过数据子树。合成测试与真实维度发现测试均通过。两项问题均已解决；问题代码为 V0.2.1 开发阶段快照，当前实现以实际修改和验证证据为准。

### ISSUE-DISCOVERY-001：无 Mod 维度时排序不可变空列表

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/preview/WorldDimensionDiscovery.java`
- **行号**：35-36
- **状态**：已修复

**问题描述**：
首版直接对 `discoverModDimensions` 返回值排序；没有 `dimensions/` 目录时返回 `List.of()`，排序会抛出 `UnsupportedOperationException`。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
List<WorldDimension> modDimensions = discoverModDimensions(worldFolder);
modDimensions.sort(Comparator.comparing(WorldDimension::id));
```

**问题分析**：
大量原版存档没有 Mod 维度目录，选中这类世界时预览准备会失败，属于常见场景功能异常。

**建议修改**：
```java
List<WorldDimension> modDimensions = new ArrayList<>(discoverModDimensions(worldFolder));
modDimensions.sort(Comparator.comparing(WorldDimension::id));
```

**影响范围**：
所有不含 Mod 维度的原版和整合包存档。

- **解决日期**：2026-07-24
- **实际修改**：在排序前创建可变 `ArrayList` 副本，最终结果仍返回不可变列表。
- **验证证据**：`WorldDimensionDiscoveryTest.keepsOverworldAvailableBeforeItsFirstRegionIsCreated` 通过。

### ISSUE-DISCOVERY-002：维度发现遍历 Region 数据子树

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/preview/WorldDimensionDiscovery.java`
- **行号**：48-59
- **状态**：已修复

**问题描述**：
首版使用完整目录遍历寻找 `region`，发现目标后仍可能进入 Region、`entities` 和 `poi` 子树，在大型整合包存档上产生不必要扫描。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
Files.walk(dimensionsRoot)
        .filter(path -> path.getFileName().toString().equals("region"));
```

**问题分析**：
维度发现发生在 JavaFX 世界选择流程中，遍历大量数据文件会造成可见延迟；任务只需要目录结构，不需要读取其中内容。

**建议修改**：
```java
if ("region".equals(directory.getFileName().toString())) {
    createModDimension(...);
    return FileVisitResult.SKIP_SUBTREE;
}
```

**影响范围**：
包含大量 Mod 维度、实体或 POI 文件的世界选择体验。

- **解决日期**：2026-07-24
- **实际修改**：改用 `walkFileTree`，发现 `region` 后记录并跳过子树，同时跳过 `entities` 和 `poi`。
- **验证证据**：真实目录只读测试发现 54 个 Mod 维度入口，未进入 Region 文件内容且测试通过。
