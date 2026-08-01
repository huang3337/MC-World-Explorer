# 代码审查：OpenGlMeshResources.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：VAO/VBO/EBO 上传与显式释放
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-GLMESH-001：上传中途失败可能泄漏已生成的 OpenGL 句柄

- **严重程度**：中
- **类别**：资源管理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/render/lwjgl/OpenGlMeshResources.java`
- **行号**：审查时 upload 35-57
- **状态**：已修复

**问题描述**：
三个句柄在返回资源对象前生成，任何 glBufferData 或属性配置异常都会跳过 close。

**当前代码（审查时快照）**：

```java
int vertexArray = glGenVertexArrays();
int vertexBuffer = glGenBuffers();
int indexBuffer = glGenBuffers();
```

**问题分析**：
当前批次上传失败时，已成功生成的句柄没有所有者负责释放。

**建议修改**：
生成过程放入 try/catch，失败时按 EBO、VBO、VAO 顺序删除。

**影响范围**：
GPU 上传错误路径和驱动资源。

- **解决日期**：2026-08-01
- **实际修改**：upload 记录各句柄并在 RuntimeException/Error 路径解绑和逆序删除。
- **验证证据**：OpenGlMeshResourcesTest、两套真实区块 OpenGL 运行和关闭验证通过。
