# 代码审查：VoxelAreaLoader.java

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `src/main/java/com/mcworldexplorer/voxel/io/VoxelAreaLoader.java`
- **问题总数**：1 个（严重 0 / 高 1 / 中 0 / 低 0）

### ISSUE-V05-LOAD-001：后台任务异常被误标为损坏区块

- **严重程度**：高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/voxel/io/VoxelAreaLoader.java`
- **行号**：审查时 76-84
- **状态**：已修复

**问题描述**：`ExecutionException` 的任意原因都会转成 `CORRUPT` 结果。

**当前代码（审查时快照）**：

```java
results.put(coordinate, ChunkLoadResult.withoutChunk(
        coordinate, ChunkLoadStatus.CORRUPT, message(cause), 0, 0));
```

**问题分析**：预期的文件和解码失败已在工作任务内结构化处理，因此逃逸到 Future 的异常代表意外程序故障，不应伪装成输入损坏。

**建议修改**：附带坐标向上抛出，保留根因。

**影响范围**：后台加载可靠性和故障诊断。

- **解决日期**：2026-08-04
- **实际修改**：将意外任务失败包装为带坐标的 `IOException`。
- **验证证据**：有限区域加载真实样本和最终全量测试通过。
