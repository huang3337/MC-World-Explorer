# 代码审查：MapTileService.java

- **审查日期**：2026-07-28
- **审查工具**：Codex
- **审查范围**：内存缓存、磁盘缓存和图块生成的三级读取链
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-MAPSERVICE-001：取消后仍可能写入已经过时的磁盘缓存

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/map/MapTileService.java`
- **行号**：44
- **状态**：已修复

**问题描述**：
图块生成完成后、写缓存前没有再次检查取消状态。

**当前代码**（审查时快照）：
```java
MapTileGenerationResult generated = generator.generate(world, dimension, key, monitor);
MapTileCacheResult stored = diskCache.store(world, dimension, key, generated);
```

**问题分析**：
用户在生成末尾切换世界、维度、图层或视口时，过时任务仍可能执行 PNG 和元数据写入，违反取消任务不落盘的约束。

**建议修改**：
```java
if (monitor.isCancelled()) {
    throw new CancellationException("map tile generation cancelled before cache write");
}
```

**影响范围**：
快速拖动、缩放以及切换世界、维度或高度带。

- **解决日期**：2026-07-28
- **实际修改**：生成返回后、磁盘写入前再次检查取消状态，并以 `CancellationException` 终止。
- **验证证据**：V0.3 最终全量 152 项测试通过；调度取消与旧请求隔离测试通过。
