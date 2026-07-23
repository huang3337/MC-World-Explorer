# 代码审查：PreviewGenerator.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：显式维度/图层生成、Region 资源复用、取消和结果统计
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

## 当前结论

当前生成器把 0 字节 Region 文件作为缺失数据处理，对非零截断文件保留失败统计，并使用共享的 `long` 范围计算处理极端整数中心。两项问题均已解决；问题代码为 V0.2.1 开发或审查阶段快照，当前实现以实际修改和验证证据为准。

### ISSUE-GENERATOR-001：0 字节 Region 占位文件被重复计为失败区块

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewGenerator.java`
- **行号**：92-139
- **状态**：已修复

**问题描述**：
真实整合包存档包含 0 字节 `.mca` 占位文件。首版将其作为损坏 Region 打开，并把该 Region 覆盖的每个请求区块重复计为失败，三组预览分别显示 2355、2782 和 1924 个失败区块。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
if (Files.isRegularFile(regionPath)) {
    reader = new RegionFileReader(regionPath);
}
```

**问题分析**：
0 字节文件不包含 Region 头和任何区块，应视为“尚无数据”。重复失败计数严重误导用户，也掩盖了实际采样成功情况；但非零截断文件仍应保持损坏提示。

**建议修改**：
```java
if (Files.size(regionPath) == 0) {
    emptyRegions.add(regionPath);
    missingChunks++;
    continue;
}
```

**影响范围**：
Better MC、Aether 和其他会预创建空 `.mca` 文件的整合包维度。

- **解决日期**：2026-07-24
- **实际修改**：新增本次生成内的空 Region 集合，0 字节文件只检查一次并按缺失处理；其他 Region 打开/区块解析失败策略不变。
- **验证证据**：空文件测试确认 `failed=0`；1 字节截断文件测试确认仍报告失败；真实下界、末地和 Aether 复验均为 `failed=0` 且有效列数不变。

### ISSUE-GENERATOR-002：极端整数中心坐标会溢出预览范围

- **严重程度**：🟡 中
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewGenerator.java`
- **行号**：审查阶段 70-79、163-164；当前实现 68-82、157-170、215-264
- **状态**：已修复

**问题描述**：
固定 1024 格预览范围曾使用 `int` 计算边界。异常或人工编辑的存档把中心设置到 32 位整数边界附近时，边界可能溢出并产生颠倒的区块范围。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
int minBlockX = center.x() - BLOCK_RANGE / 2;
int maxBlockX = minBlockX + BLOCK_RANGE - 1;
int minChunkX = Math.floorDiv(minBlockX, 16);
int maxChunkX = Math.floorDiv(maxBlockX, 16);
```

**问题分析**：
中心值接近 `Integer.MIN_VALUE` 或 `Integer.MAX_VALUE` 时，方块边界加减会发生有符号整数溢出。生成循环和缓存 Region 状态计算可能得到不同或颠倒的范围，产生空图或错误缓存判断。

**建议修改**：
```java
long minBlockX = (long) center.x() - BLOCK_RANGE / 2L;
long maxBlockX = minBlockX + BLOCK_RANGE - 1L;
```

生成器和缓存必须复用同一范围对象，并在局部像素索引确认位于 `0..BLOCK_RANGE-1` 后再转换为 `int`。

**影响范围**：
坐标接近 32 位整数边界的损坏、Mod 或人工编辑存档，以及对应预览缓存失效判断。

- **解决日期**：2026-07-24
- **实际修改**：新增共享的 `PreviewBounds` 计算，使用 `long` 完成方块边界、区块边界和局部偏移运算；`PreviewGenerator` 与 `PreviewCache` 共用该结果，避免生成范围和缓存失效范围不一致。
- **验证证据**：`PreviewGeneratorRequestTest.keepsPreviewBoundsOrderedAtExtremeIntegerCoordinates` 覆盖 `Integer.MIN_VALUE` 和 `Integer.MAX_VALUE` 附近中心；默认环境与真实目录全量测试均通过。
