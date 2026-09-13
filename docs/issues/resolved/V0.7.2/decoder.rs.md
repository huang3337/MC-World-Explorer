# 代码审查：decoder.rs

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 5 双布局 Chunk/Palette 解码
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-CHUNKDEC-001：Chunk NBT 绕过统一结构预检

- **严重程度**：🟠 高
- **类别**：安全性
- **文件**：`crates/mcwe-core/src/chunk/decoder.rs`
- **行号**：审查时约 75–85 行，`decode_chunk`
- **状态**：已修复

**问题描述**：
Chunk 解码初版直接调用 fastnbt，并把累计文本字节上限传给 `max_seq_len`；它没有复用 Task 3 建立的深度、标签总数、序列和累计文本预检。

**问题分析**：
合法 Region 载荷仍可能包含过深的未知 NBT 子树。Serde 即使忽略该字段，底层反序列化仍需遍历它，因而不能只依赖目标 DTO 和解压字节上限。

**建议修改**：
所有 NBT 领域入口统一通过 `nbt::decode_nbt`，先执行受限结构预检，再调用锁定版 fastnbt。

**影响范围**：
现代和旧版 Chunk 布局；不改变公开领域模型、Region 读取或存档只读边界。

- **解决日期**：2026-09-07。
- **实际修改**：`decode_chunk` 改为复用 `decode_nbt(bytes, NBT_OUTPUT_BYTES, cancel)`，移除错误的直接 fastnbt 配置。
- **验证证据**：新增过深未知 Chunk NBT 回归测试；修复前未返回资源上限错误，修复后稳定返回 `ResourceLimit`。
