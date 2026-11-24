# 代码审查：WorldDimension.java

- **审查日期**：2026-11-24
- **审查工具**：Gemini
- **审查范围**：传统数字维度 ID 解析与归一化
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-WORLDDIM-001：传统数字维度正则遗漏 10 位 32-bit 整数并接受前导零别名

- **严重程度**：🟡 中
- **类别**：安全性 / 代码质量
- **文件**：`src/main/java/com/mcworldexplorer/preview/WorldDimension.java`
- **行号**：16, 66–81（审查时快照）
- **状态**：已修复

**问题描述**：
`LEGACY_NUMERIC_DIMENSION` 仅匹配 1–9 位数字且未校验前导零，既漏掉了合法的 10 位 32-bit `int` 维度号（如 `Integer.MIN_VALUE`），又会把 `"DIM00"`、`"01"` 误归一为 `"legacy:dim_0"`、`"legacy:dim_1"`。

**当前代码**（审查时快照）：
```java
private static final Pattern LEGACY_NUMERIC_DIMENSION = Pattern.compile("^(?:DIM)?(-?[0-9]{1,9})$");
```

**问题分析**：
旧版 Forge `DimensionManager` 使用完整 32 位有符号 `int` 注册维度，部分 Mod 采用哈希值产生 10 位维度号；同时前导零目录不应绕过原版 `0`/`-1`/`1` 判定生成重复维度别名。

**建议修改**：
提供 `parseCanonicalInt(String)`，仅接受无前导零且在 `Integer.MIN_VALUE..Integer.MAX_VALUE` 范围内的十进制文本，并统一处理 `0`、`-1`、`1` 与其余整数。

**影响范围**：
玩家与出生点数值维度归一化、传统 Mod 维度匹配。

- **解决日期**：2026-11-24
- **实际修改**：新增 `WorldDimension.parseCanonicalInt` 并在 `normalizeId` 中统一分派。
- **验证证据**：`WorldDimensionDiscoveryTest` 覆盖 `Integer.MIN_VALUE`、`DIM2147483648` 溢出与 `DIM00` 前导零场景并通过回归。
