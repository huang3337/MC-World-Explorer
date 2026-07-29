# 代码审查：MapTileGenerator.java

- **审查日期**：2026-07-28
- **审查工具**：Codex
- **审查范围**：Region 预检、中心优先读取、渐进地形快照和传送门提取
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-MAPGEN-001：标记提取异常会连带丢失当前区块地形

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/map/MapTileGenerator.java`
- **行号**：96-102
- **状态**：已修复

**问题描述**：
传送门提取与地形采样位于同一个异常边界内，标记异常会使当前区块整体计为失败。

**当前代码**（审查时快照）：
```java
ChunkSurface surface = surfaceSampler.sample(parsed, key.layer());
markers.addAll(portalExtractor.extract(parsed, key.layer(), dimension.id(), chunkX, chunkZ));
```

**问题分析**：
标记属于可降级叠加能力，不应阻止已经成功取得的地形继续渲染。

**建议修改**：
```java
try {
    markers.addAll(portalExtractor.extract(...));
} catch (RuntimeException failure) {
    LOGGER.warn("Failed to extract portal markers", failure);
}
```

**影响范围**：
含异常 Section 或未来不兼容传送门状态的区块。

- **解决日期**：2026-07-28
- **实际修改**：为标记提取增加独立异常边界并记录区块坐标，地形采样继续完成。
- **验证证据**：地图生成、透明局部快照、标记合并、真实四类维度图块测试及全量测试通过。
