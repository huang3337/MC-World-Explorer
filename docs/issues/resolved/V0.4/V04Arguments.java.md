# 代码审查：V04Arguments.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：命令行输入、只读存档边界和显式输出路径
- **问题总数**：2 个（严重 1 / 高 1 / 中 0 / 低 0）

### ISSUE-V04ARGS-001：输出路径的词法检查可被符号链接绕过

- **严重程度**：严重
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/V04Arguments.java`
- **行号**：审查时 52-58
- **状态**：已修复

**问题描述**：
报告或截图只用 normalize 和 startsWith 检查，若父目录是指向存档内部的符号链接，可能把输出写进存档。

**当前代码（审查时快照）**：

```java
if (report.isPresent() && report.orElseThrow().startsWith(world)) {
    throw new IllegalArgumentException("report path must be outside the world directory");
}
```

**问题分析**：
V0.4 明确要求存档严格只读。词法路径不解析链接，无法证明实际写入目标位于存档外。

**建议修改**：
对存档使用 toRealPath；对尚不存在的输出解析最近存在祖先的真实路径，再拼接剩余路径并比较。

**影响范围**：
显式报告和截图输出的存档只读边界。

- **解决日期**：2026-08-01
- **实际修改**：存档路径改为真实路径；报告和截图解析最近存在祖先的真实路径并返回规范化结果。
- **验证证据**：V04ArgumentsTest、三轮真实存档 SHA-256 前后清单比较均通过。

### ISSUE-V04ARGS-002：极端区块坐标可在世界坐标换算时溢出

- **严重程度**：高
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/V04Arguments.java`
- **行号**：审查时 47-48
- **状态**：已修复

**问题描述**：
参数接受完整 int 范围，但网格和邻区块逻辑会执行 chunk*16、+15 和 +/-1。

**当前代码（审查时快照）**：

```java
int chunkX = parseCoordinate(values.get("--chunk-x"), "--chunk-x");
int chunkZ = parseCoordinate(values.get("--chunk-z"), "--chunk-z");
```

**问题分析**：
极端输入会抛出未面向用户的 ArithmeticException，或在邻区块坐标上溢出。

**建议修改**：
在参数解析阶段限制为可安全完成目标及四邻世界坐标换算的范围。

**影响范围**：
异常命令行输入和所有后续世界坐标计算。

- **解决日期**：2026-08-01
- **实际修改**：新增 parseChunkCoordinate，预留四邻和区块内 15 格的安全范围。
- **验证证据**：V04ArgumentsTest 新增 Integer.MAX_VALUE 拒绝用例并通过。
