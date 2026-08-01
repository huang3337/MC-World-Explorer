# 代码审查：LwjglV04Renderer.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：GLFW 窗口、OpenGL 循环、截图与资源生命周期
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-LWJGLRENDER-001：部分初始化失败时 GLFW 和线程能力清理状态不明确

- **严重程度**：中
- **类别**：资源管理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/render/lwjgl/LwjglV04Renderer.java`
- **行号**：审查时 close 153-177
- **状态**：已修复

**问题描述**：
close 无条件 glfwTerminate，且销毁上下文前未清除 LWJGL 线程本地 capabilities。

**当前代码（审查时快照）**：

```java
glfwDestroyWindow(window);
glfwTerminate();
```

**问题分析**：
GLFW 初始化失败或 GL capabilities 创建到一半时，清理顺序可能调用不适用 API或保留线程上下文引用。

**建议修改**：
显式记录 GLFW/capabilities 成功状态，先删 GPU 资源，再 GL.setCapabilities(null)、销毁窗口、终止 GLFW。

**影响范围**：
启动失败、正常退出和便携目录文件句柄验证。

- **解决日期**：2026-08-01
- **实际修改**：增加两个初始化状态标记并按依赖顺序清理。
- **验证证据**：便携副本启动退出后目录可整体移动；120 秒运行均正常结束。
