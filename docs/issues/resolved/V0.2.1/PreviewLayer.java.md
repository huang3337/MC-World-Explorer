# 代码审查：PreviewLayer.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：地表总览与 32 格高度带值约束、缓存键和显示文本
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

## 当前结论

当前构造器要求 `SURFACE_OVERVIEW` 使用规范的 `0..0` 范围，缓存键和显示文本由规范值稳定生成。问题代码为 V0.2.1 开发阶段快照，当前实现以实际修改和验证证据为准。

### ISSUE-LAYER-001：地表总览允许携带任意高度范围

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewLayer.java`
- **行号**：11-19
- **状态**：已修复

**问题描述**：
首版只校验高度带宽度，没有限制 `SURFACE_OVERVIEW` 的 `minY/maxY`，相同语义可能产生多个不相等对象和不同元数据。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
if (type == PreviewLayerType.HEIGHT_BAND && minY > maxY) {
    throw new IllegalArgumentException(...);
}
```

**问题分析**：
地表总览不使用高度范围，允许任意值会破坏值对象规范化并增加缓存身份歧义。

**建议修改**：
```java
if (type == PreviewLayerType.SURFACE_OVERVIEW && (minY != 0 || maxY != 0)) {
    throw new IllegalArgumentException(...);
}
```

**影响范围**：
图层相等性、缓存元数据匹配和 UI 选择状态。

- **解决日期**：2026-07-24
- **实际修改**：地表总览固定使用单例规范值 `0..0`，高度带继续限制为 1..32 格。
- **验证证据**：`PreviewLayerTest.rejectsInvalidRangesAndOversizedBands` 通过。
