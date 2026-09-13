# 代码审查：protocol/mesh_v1.rs

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 8 Mesh v1 数组、批次和元数据语义
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

### ISSUE-MESHPROTO-001：批次范围加法可溢出并触发 panic

- **严重程度**：🟠 高
- **类别**：协议安全
- **文件**：`src-tauri/src/protocol/mesh_v1.rs`
- **状态**：已修复

**问题描述**：
初版先执行 `first_index + index_count`，再尝试转换为 `usize`；恶意或错误 DTO 可在 debug 构建中先发生整数溢出 panic。

**问题分析**：
转换检查无法保护此前已经发生的 u32 加法，且初版未要求批次连续覆盖、批次 faceCount 与 indexCount 一致。

**建议修改**：
先使用 `checked_add`，再验证范围、连续覆盖、6 倍数、批次与总体 block/face 统计。

**影响范围**：
Mesh batch table 编码和异常任务隔离。

- **解决日期**：2026-09-07。
- **实际修改**：所有批次算术改为受检操作，并收紧连续覆盖、颜色、alpha 和统计不变量。
- **验证证据**：u32 溢出、批次 faceCount 和总体 faceCount 不一致测试均被受控拒绝。

### ISSUE-MESHPROTO-002：编码器丢弃核心 bounds/warnings 并使用旧推导原点

- **严重程度**：🟡 中
- **类别**：正确性
- **文件**：`src-tauri/src/protocol/mesh_v1.rs`
- **状态**：已修复

**问题描述**：
Task 7 已向 `MeshData` 增加原点、bounds 和 warnings，但初版协议仍写 `bounds:null`、`warnings:[]`，并用 `rect.min*16` 重算原点。

**问题分析**：
这会静默丢失局部失败和 fallback 诊断，同时无法证明 metadata 与实际数组、选区和核心结果一致。

**建议修改**：
校验并编码核心原点、有限 bounds、轴向 normals、资源上限、索引和统计；对 warning 类型组合与字符串进行有界 JSON 转义。

**影响范围**：
三维视图定位、包围盒、局部降级提示和非 ASCII 模组方块名。

- **解决日期**：2026-09-07。
- **实际修改**：完整映射 bounds/warnings，新增 JSON 转义、warning 组合校验及 mesh 数组语义校验。
- **验证证据**：固定 mesh 黄金向量、中文/引号/反斜杠 warning、NaN、非轴向法线和索引越界测试通过。
