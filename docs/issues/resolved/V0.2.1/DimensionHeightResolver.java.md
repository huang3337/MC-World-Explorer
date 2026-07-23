# 代码审查：DimensionHeightResolver.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：原版固定高度与 Mod 维度实际 Section 高度探测
- **问题总数**：2 个（🔴 0 / 🟠 2 / 🟡 0 / 🟢 0）

## 当前结论

当前实现会遍历排序后的候选 Region，直到取得 8 个有效区块，并把异常隔离在单区块循环。`derivesModRangeFromActualChunkSections` 和 `skipsUnreadableChunksAndContinuesWithinTheSameRegion` 均通过。本文件保留两项开发阶段问题，代码片段为当时快照；当前实现以实际修改和验证证据为准。

### ISSUE-HEIGHT-001：只检查最前面的少量 Region 文件

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/preview/DimensionHeightResolver.java`
- **行号**：40-53
- **状态**：已修复

**问题描述**：
首版只取排序后的前 4 个 Region 文件；Aether 真实样本中这些文件为空或不可解析，而后续文件存在有效区块，导致整个维度高度探测失败。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
regionFiles = paths.sorted().limit(4).toList();
```

**问题分析**：
Region 文件名顺序不代表数据有效性，固定截断会让实际可支持的 Mod 维度错误显示为不可用。

**建议修改**：
```java
for (Path regionFile : regionFiles) {
    // Continue until enough valid chunks are sampled.
}
```

**影响范围**：
存在空 Region 占位文件、稀疏生成区域或早期损坏文件的 Mod 维度。

- **解决日期**：2026-07-24
- **实际修改**：按稳定顺序继续扫描全部候选 Region，获得 8 个有效区块后提前结束，不再按文件数量武断截断。
- **验证证据**：真实 `aether:the_aether` 成功取得高度范围并生成 38303 个有效图像列。

### ISSUE-HEIGHT-002：单个坏区块会放弃整个 Region

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/preview/DimensionHeightResolver.java`
- **行号**：55-76
- **状态**：已修复

**问题描述**：
首版异常捕获位于 Region 读取循环外，任一坏区块会中断该文件后续 1023 个位置的探测。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
try (RegionFileReader reader = new RegionFileReader(regionFile)) {
    for (...) {
        Optional<RegionChunkData> chunk = reader.readChunk(localX, localZ);
        sampler.sectionRange(chunk.orElseThrow());
    }
} catch (IOException e) {
    lastFailure = e;
}
```

**问题分析**：
这违反项目既有“单区块失败隔离”策略；同一 Region 后面可能仍有大量正常区块，不能因一个异常全部丢弃。

**建议修改**：
```java
for (...) {
    try {
        // Read and sample one chunk.
    } catch (IOException e) {
        lastFailure = e;
    }
}
```

**影响范围**：
部分损坏、部分不支持或混合历史区块布局的 Mod 维度。

- **解决日期**：2026-07-24
- **实际修改**：把异常隔离下沉到单区块循环，Region 打开失败仍在文件级隔离，并保留最终失败原因。
- **验证证据**：新增“第一个区块不可读、第二个区块有效”的合成 Region 测试，探测结果为 `-16..63`。
