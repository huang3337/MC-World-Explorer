# 代码审查：FrameTimeRecorder.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：固定上限帧时长采样和分位数统计
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-FRAMETIME-001：Long 装箱让测量器自身制造额外堆分配

- **严重程度**：中
- **类别**：资源管理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/metrics/FrameTimeRecorder.java`
- **行号**：审查时 8-18
- **状态**：已修复

**问题描述**：
每帧时长存入 List<Long>，在 120 秒高帧率测量中持续创建装箱对象。

**当前代码（审查时快照）**：

```java
private final List<Long> samples = new ArrayList<>();
...
samples.add(frameNanos);
```

**问题分析**：
虽有 120000 条上限，但会污染本来要观察的内存趋势。

**建议修改**：
使用固定 long[] 和计数器，汇总时仅复制已用范围。

**影响范围**：
性能证据的可信度和长时间试验堆占用。

- **解决日期**：2026-08-01
- **实际修改**：改为预分配 long[]；平均值用 double 累加，避免极端总和溢出。
- **验证证据**：FrameTimeRecorderTest 及最终两端 120 秒稳定性复核通过。
