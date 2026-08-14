# 代码审查：VoxelSceneBuilder.java

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `src/main/java/com/mcworldexplorer/voxel/mesh/VoxelSceneBuilder.java`
- **问题总数**：1 个（严重 0 / 高 1 / 中 0 / 低 0）

### ISSUE-V05-SCENE-001：意外运行时异常被伪装为损坏区块

- **严重程度**：高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/voxel/mesh/VoxelSceneBuilder.java`
- **行号**：审查时 43-44
- **状态**：已修复

**问题描述**：网格阶段捕获所有 `RuntimeException` 并把目标状态改为 `CORRUPT`。

**当前代码（审查时快照）**：

```java
} catch (ChunkDecodeException | RuntimeException e) {
    statuses.put(coordinate, ChunkLoadStatus.CORRUPT);
}
```

**问题分析**：坐标溢出、空指针或程序缺陷会被误报为存档损坏，降低可诊断性并可能产生错误的部分成功。

**建议修改**：只隔离明确的数据解码异常和取消，其他程序异常向上暴露。

**影响范围**：多区块网格任务的错误归因和可靠性。

- **解决日期**：2026-08-04
- **实际修改**：移除对通用运行时异常的捕获。
- **验证证据**：体素定向测试及最终全量测试通过。
