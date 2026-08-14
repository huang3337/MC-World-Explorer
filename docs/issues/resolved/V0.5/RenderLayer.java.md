# 代码审查：RenderLayer.java

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `src/main/java/com/mcworldexplorer/voxel/mesh/RenderLayer.java`
- **问题总数**：1 个（严重 0 / 高 1 / 中 0 / 低 0）

### ISSUE-V05-LAYER-001：不透明降级层排在透明层之后绘制

- **严重程度**：高
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/voxel/mesh/RenderLayer.java`
- **行号**：审查时 3-8
- **状态**：已修复

**问题描述**：资源按枚举顺序绘制，但 `FALLBACK` 位于 `TRANSLUCENT` 和 `FLUID` 之后。

**当前代码（审查时快照）**：

```java
OPAQUE, CUTOUT, TRANSLUCENT, FLUID, FALLBACK
```

**问题分析**：完全不透明的降级几何后绘制会与已写入深度的透明层产生错误遮挡。

**建议修改**：所有不透明层先于透明和流体层绘制。

**影响范围**：含 Mod 方块、玻璃或流体场景的视觉正确性。

- **解决日期**：2026-08-04
- **实际修改**：顺序改为 `OPAQUE, FALLBACK, CUTOUT, TRANSLUCENT, FLUID`。
- **验证证据**：定向测试、最终 8x8 与四类维度截图均通过。
