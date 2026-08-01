# 代码审查：VoxelChunk.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：区块 Section 索引、负 Y 查询和垂直范围
- **问题总数**：1 个（严重 0 / 高 1 / 中 0 / 低 0）

### ISSUE-VOXCHUNK-001：重复或越界 Section Y 以非受控异常进入管线

- **严重程度**：高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/data/VoxelChunk.java`
- **行号**：审查时 14-19
- **状态**：已修复

**问题描述**：
Collectors.toUnmodifiableMap 在重复 Y 时抛出通用 IllegalStateException，且未验证 Y*16 的整数范围。

**当前代码（审查时快照）**：

```java
this.sections = sections.stream().collect(Collectors.toUnmodifiableMap(
        VoxelSection::sectionY,
        Function.identity()));
```

**问题分析**：
损坏区块可能绕过 VoxelDataException 契约，并在网格阶段崩溃。

**建议修改**：
显式构建排序映射，检测重复 Y 和坐标溢出并抛出带 Section Y 的 VoxelDataException。

**影响范围**：
损坏或恶意区块 NBT 的失败隔离。

- **解决日期**：2026-08-01
- **实际修改**：改用 TreeMap 索引，增加重复与整数范围检查，并公开排序 sectionYs。
- **验证证据**：VoxelChunkParserTest 的重复 Section 用例和全部 V0.4 测试通过。
