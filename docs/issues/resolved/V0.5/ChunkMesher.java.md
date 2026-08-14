# 代码审查：ChunkMesher.java

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `src/main/java/com/mcworldexplorer/voxel/mesh/ChunkMesher.java`
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-V05-MESH-001：未知方块警告未记录出现次数且跨区块重复

- **严重程度**：中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/voxel/mesh/ChunkMesher.java`
- **行号**：审查时 55-68
- **状态**：已修复

**问题描述**：同一未知方块只在单区块内去重，场景报告会重复列出相同 ID，也无法判断实际影响规模。

**当前代码（审查时快照）**：

```java
warnings.putIfAbsent("fallback:" + state.name(), new MeshWarning(...));
```

**问题分析**：不符合按方块类型汇总警告和次数的设计要求，大型 Mod 样本报告可读性差。

**建议修改**：在网格阶段累计出现次数，并在场景阶段按方块 ID 跨区块合并。

**影响范围**：未知及 Mod 方块诊断报告。

- **解决日期**：2026-08-04
- **实际修改**：新增警告累计器和 `occurrences`，真实 8x8 报告按 205 种警告汇总 506553 次出现。
- **验证证据**：`ChunkMesherTest`、`VoxelSceneBuilderTest` 与真实 8x8 JSON 校验通过。
