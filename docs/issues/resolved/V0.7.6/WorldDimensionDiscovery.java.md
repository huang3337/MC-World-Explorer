# 代码审查：WorldDimensionDiscovery.java

- **审查日期**：2026-11-24
- **审查工具**：Gemini
- **审查范围**：存档根下 `DIM<整数>` 传统维度目录扫描
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-DISCOVERY-004：`DIM<整数>` 目录扫描重复维护 9 位正则边界

- **严重程度**：🟡 中
- **类别**：代码质量 / 模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/preview/WorldDimensionDiscovery.java`
- **行号**：21, 56–62（审查时快照）
- **状态**：已修复

**问题描述**：
`WorldDimensionDiscovery` 独立定义 `^DIM(-?[0-9]{1,9})$`，与 `WorldDimension` 重复维护数字维度规则，同样不支持 10 位 32-bit `int` 维度目录且未过滤 `DIM00` 前导零目录。

**当前代码**（审查时快照）：
```java
private static final Pattern LEGACY_DIM_DIR = Pattern.compile("^DIM(-?[0-9]{1,9})$");
```

**问题分析**：
目录发现与维度 ID 归一化需要完全一致的整数解析边界，否则会出现玩家所在维度可解析但目录未被发现（或相反）的不一致。

**建议修改**：
复用 `WorldDimension.parseCanonicalInt(name.substring(3))` 统一解析并跳过 `0`、`-1`、`1` 及非规范目录名。

**影响范围**：
传统 Forge Mod 存档的维度列表发现。

- **解决日期**：2026-11-24
- **实际修改**：删除重复正则，统一调用 `WorldDimension.parseCanonicalInt`。
- **验证证据**：`WorldDimensionDiscoveryTest` 验证 `DIM-2147483648` 正常发现、`DIM00` 与 `DIM2147483648` 安全忽略。
