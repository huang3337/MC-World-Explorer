# 代码审查：ShaderProgram.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：OpenGL 着色器读取、编译、链接和 uniform 校验
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-SHADER-001：片段着色器编译失败时顶点着色器可能泄漏

- **严重程度**：中
- **类别**：资源管理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/render/lwjgl/ShaderProgram.java`
- **行号**：审查时构造函数 35-58
- **状态**：已修复

**问题描述**：
顶点着色器先创建，片段着色器编译若抛出，旧结构尚未进入删除两者的 finally。

**当前代码（审查时快照）**：

```java
int vertex = compile(...);
int fragment = compile(...);
int created = glCreateProgram();
```

**问题分析**：
异常着色器或驱动错误路径会遗留 GPU 句柄直到上下文销毁。

**建议修改**：
使用嵌套 try/finally，分别保证 vertex、fragment 和 program 在每条失败路径删除。

**影响范围**：
OpenGL 初始化失败和重复试验的资源释放。

- **解决日期**：2026-08-01
- **实际修改**：构造函数改为嵌套资源清理，uniform 缺失也会删除已链接 program。
- **验证证据**：ShaderProgramTest、真实 OpenGL 启动和退出验证通过。
