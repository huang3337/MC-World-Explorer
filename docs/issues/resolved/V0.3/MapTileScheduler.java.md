# 代码审查：MapTileScheduler.java

- **审查日期**：2026-07-28
- **审查工具**：Codex
- **审查范围**：双线程优先级调度、取消和旧请求隔离
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

### ISSUE-MAPSCHED-001：后台工作线程可能阻止应用正常退出

- **严重程度**：🟡 中
- **类别**：资源管理
- **文件**：`src/main/java/com/mcworldexplorer/map/MapTileScheduler.java`
- **行号**：21-27
- **状态**：已修复

**问题描述**：
线程池使用默认线程工厂，创建的非守护线程在窗口关闭后仍可能保持 JVM 存活。

**当前代码**（审查时快照）：
```java
executor = new ThreadPoolExecutor(
        WORKER_COUNT, WORKER_COUNT, 0, TimeUnit.MILLISECONDS,
        new PriorityBlockingQueue<>());
```

**问题分析**：
控制器没有单独的窗口销毁回调，正在等待或执行的非守护线程可能让应用退出表现异常。

**建议修改**：
```java
runnable -> {
    Thread thread = new Thread(runnable, "map-tile-worker");
    thread.setDaemon(true);
    return thread;
}
```

**影响范围**：
地图任务尚未全部完成时关闭主窗口。

- **解决日期**：2026-07-28
- **实际修改**：使用有序命名的守护线程工厂，同时保留显式 `close()` 和取消能力。
- **验证证据**：并发上限、取消、旧请求隔离和全量测试通过。

### ISSUE-MAPSCHED-002：跨请求保留的图块仍使用旧回调

- **严重程度**：🟠 高
- **类别**：异步状态
- **文件**：`src/main/java/com/mcworldexplorer/map/MapTileScheduler.java`
- **行号**：37-73
- **状态**：已修复

**问题描述**：
新视口请求会更新保留任务的请求编号，但 `submit` 发现相同键已存在后直接返回，任务完成时仍调用旧请求创建的成功或失败回调。

**问题分析**：
控制器会正确拒绝旧回调，因此同一可见图块可能已经生成完成却永远不进入当前视口，状态持续停留在“正在补全”。

**实际修改**：
`beginRequest` 保留任务时不提前改写所有权；当前请求重新提交同一键时，原任务会重新绑定最新请求编号、优先级和完成回调。排队中的任务同时按新优先级重新入队，执行中的任务继续复用已有读取工作。

**验证证据**：
新增 `rebindsRetainedTileToLatestRequestCallback` 回归测试，确认只向第二次请求回调交付结果；相关目标测试通过。
