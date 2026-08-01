# 代码审查：VoxelMesher.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：目标区块表面剔除、流体外壳和网格批次生成
- **问题总数**：1 个（严重 0 / 高 1 / 中 0 / 低 0）

### ISSUE-VOXMESH-001：稀疏且相距很远的 Section 会触发巨大空高度遍历

- **严重程度**：高
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/mesh/VoxelMesher.java`
- **行号**：审查时 42-45
- **状态**：已修复

**问题描述**：
网格器从 minY 连续循环到 maxY，即使中间没有 Section 也逐层查询。

**当前代码（审查时快照）**：

```java
for (int y = minY; y <= maxY; y++) {
    for (int localZ = 0; localZ < 16; localZ++) {
```

**问题分析**：
异常 NBT 可把单区块任务放大为数百万高度层，造成长时间卡死。

**建议修改**：
只遍历目标区块实际存在的 Section，每个 Section 固定遍历 16 层。

**影响范围**：
损坏/稀疏区块的性能和拒绝服务风险。

- **解决日期**：2026-08-01
- **实际修改**：改为遍历排序 sectionYs，并在每个 Section 内遍历 0..15。
- **验证证据**：VoxelMesherTest 用相距 200000 个 Section 的样本在 2 秒限制内完成，全部几何测试通过。
