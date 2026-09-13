# 代码审查：limited_reader.rs

- **审查日期**：2026-09-07
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 3 受限 NBT 解压与反序列化边界
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

### ISSUE-NBTLIMIT-001：进入 fastnbt 前没有限制 NBT 嵌套深度

- **严重程度**：🟠 高
- **类别**：安全性
- **文件**：`crates/mcwe-core/src/nbt/limited_reader.rs`
- **行号**：审查时约 27–45 行，`decode_nbt`
- **状态**：已修复

**问题描述**：
初版只限制压缩输入、解压字节并设置 `fastnbt::DeOpts::max_seq_len`，没有在第三方反序列化前限制 Compound/List 嵌套深度。

**当前代码**（审查时快照）：
```rust
fastnbt::from_bytes_with_opts(
    &decoded,
    fastnbt::DeOpts::new().max_seq_len(max_output),
)
```

**问题分析**：
锁定版 fastnbt 的 `max_seq_len` 控制序列长度，不提供嵌套深度选项。损坏输入可使解析器进入过深递归，不满足 DECISION-018 和 Task 3 的可验证资源边界。

**建议修改**：
在 fastnbt 前使用无领域对象分配的结构预检，限制深度、序列、标签数和累计文本，并拒绝截断、负长度、尾随数据及非法标签。

**影响范围**：
`level.dat` 和后续复用同一入口的区块 NBT 解析；不影响 Java `main`，不写入世界。

- **解决日期**：2026-09-07。
- **实际修改**：新增 `nbt/preflight.rs`，在调用 fastnbt 前强制 `64` 层深度、单序列 `1,000,000`、累计标签 `1,000,000` 和累计文本 `256 KiB` 上限；损坏压缩流统一映射为安全的 `InvalidNbt`。
- **验证证据**：新增独立 `tests/level_dat.rs` 与预检单元测试；过深嵌套测试先失败，修复后通过，覆盖负长度、超长序列、截断数组和尾随数据。

### ISSUE-NBTLIMIT-002：List 预记账会对嵌套 Compound 触发错误断言

- **严重程度**：🟡 中
- **类别**：正确性
- **文件**：`crates/mcwe-core/src/nbt/preflight.rs`
- **行号**：审查时约 95–100 行，`payload_without_count`
- **状态**：已修复

**问题描述**：
List 会先计入全部直接元素，再逐个解析其载荷。初版在解析后断言标签数必须回到解析前，但 Compound/List 元素的后代标签应合法增加总数，因此调试构建会错误崩溃。

**问题分析**：
该断言只适用于无后代的标量元素，并且“先重复计数、解析后再扣除”的顺序会在标签总数恰好处于上限时造成误拒绝。

**建议修改**：
解析已预记账的 List 元素前先撤销该元素的一次计数，再复用统一的 `payload` 入口重新计入元素及其全部后代。

**影响范围**：
包含 List<Compound> 或嵌套 List 的合法 NBT；不影响只读边界和对外数据模型。

- **解决日期**：2026-09-07。
- **实际修改**：`payload_without_count` 改为先扣除 List 已预记账的当前元素，再调用统一解析逻辑，避免双计数且保留后代标签计数。
- **验证证据**：新增 `accepts_compounds_nested_in_a_list` 回归测试；修复前稳定触发断言失败，修复后通过。
