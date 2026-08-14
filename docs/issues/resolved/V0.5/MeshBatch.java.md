# 代码审查：MeshBatch.java

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `src/main/java/com/mcworldexplorer/voxel/mesh/MeshBatch.java`
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-V05-BATCH-001：网格批次无法表达分层方块数

- **严重程度**：中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/voxel/mesh/MeshBatch.java`
- **行号**：审查时 6-13
- **状态**：已修复

**问题描述**：批次只有面、顶点和索引，报告层无法在不重新读取体素数据的情况下统计每层方块数。

**当前代码（审查时快照）**：

```java
public record MeshBatch(RenderLayer layer, int rgb, float alpha,
        float[] positions, float[] normals, int[] indices) {
```

**问题分析**：迫使报告层违反快照边界或遗漏既定证据。

**建议修改**：在生成批次时累计不可变 `blockCount`，快照构造器校验总数。

**影响范围**：后端无关统计和报告完整性。

- **解决日期**：2026-08-04
- **实际修改**：批次新增方块数，区块快照校验分层和总量一致。
- **验证证据**：最终 8x8 报告的层方块总数等于 3451622。
