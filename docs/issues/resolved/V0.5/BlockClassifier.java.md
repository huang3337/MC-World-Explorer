# 代码审查：BlockClassifier.java

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `src/main/java/com/mcworldexplorer/voxel/mesh/BlockClassifier.java`
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-V05-CLASS-001：染色玻璃使用名称后缀猜测分类

- **严重程度**：中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/voxel/mesh/BlockClassifier.java`
- **行号**：审查时 70-91
- **状态**：已修复

**问题描述**：原实现通过 `endsWith("_stained_glass")` 判断透明类别。

**当前代码（审查时快照）**：

```java
if (TRANSLUCENT.contains(name) || isStainedGlass(name)) {
```

**问题分析**：违反已确认的显式注册规则，名称启发式会让未来未知 ID 被静默猜测。

**建议修改**：显式登记原版染色玻璃及玻璃板，未知命名空间始终降级。

**影响范围**：透明分类、Mod 降级一致性。

- **解决日期**：2026-08-04
- **实际修改**：删除后缀判断，加入 32 个明确 ID。
- **验证证据**：`BlockClassifierTest.usesExplicitRegistrationInsteadOfNameGuessing` 通过。
