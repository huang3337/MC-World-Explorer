# 代码审查：VoxelChunkParser.java

- **审查日期**：2026-08-01
- **审查工具**：Codex
- **审查范围**：现代与旧版完整方块状态 NBT 解析
- **问题总数**：3 个（严重 0 / 高 2 / 中 1 / 低 0）

### ISSUE-VOXPARSE-001：空属性值可让 IllegalArgumentException 逃逸

- **严重程度**：高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/data/VoxelChunkParser.java`
- **行号**：审查时 151
- **状态**：已修复

**问题描述**：
属性类型虽被检查，但空字符串在 VoxelBlockState 构造时抛出运行时异常，未转换为解析错误。

**当前代码（审查时快照）**：

```java
return new VoxelBlockState(name, properties);
```

**问题分析**：
损坏调色板会绕过 VoxelDataException 和邻区块警告逻辑，导致试验进程直接失败。

**建议修改**：
捕获状态构造的 IllegalArgumentException，包装为 INVALID_PALETTE_PROPERTY。

**影响范围**：
异常 Palette Properties 的局部失败处理。

- **解决日期**：2026-08-01
- **实际修改**：构造失败现统一包装为带 Section Y 和调色板索引上下文的 VoxelDataException。
- **验证证据**：VoxelChunkParserTest 新增空属性测试并通过。

### ISSUE-VOXPARSE-002：预扁平化旧式 Section 被静默解析为空区块

- **严重程度**：高
- **类别**：逻辑错误
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/data/VoxelChunkParser.java`
- **行号**：解析 `Level/Sections` 的 Section 收集阶段
- **状态**：已修复

**问题描述**：
旧存档可能使用 `Blocks/Data` 而没有 `Palette/BlockStates`。原实现会跳过所有 Section 并返回空 `VoxelChunk`，使不支持的真实内容看起来像成功解析的空气区块。

**问题分析**：
静默空结果会掩盖版本不兼容，无法区分“真实空区块”和“解析器不支持该方块存储格式”，直接影响三维内容正确性判断。

**建议修改**：
当 `Level/Sections` 非空但没有任何可解析的调色板 Section 时，明确抛出 `UNSUPPORTED_CHUNK_LAYOUT`。

**影响范围**：
预扁平化旧存档和其他不含调色板方块状态的旧式 Section。

- **解决日期**：2026-08-01
- **实际修改**：旧式 Section 不再静默返回空区块；支持的调色板布局继续正常解析，不支持布局明确失败。
- **验证证据**：新增预扁平化合成测试；25 个真实世界跨版本验证中，RLCraft 存档被明确归类为不支持，其余 24 个调色板世界逐方块对照通过。

### ISSUE-VOXPARSE-003：未校验 NBT 区块坐标与 Region 槽位一致性

- **严重程度**：中
- **类别**：数据正确性
- **文件**：`src/main/java/com/mcworldexplorer/experimental/v04/data/VoxelChunkParser.java`
- **行号**：布局识别后、Section 解析前
- **状态**：已修复

**问题描述**：
解析器直接采用调用方传入的区块坐标，没有核对 NBT 中的 `xPos/zPos`。损坏或错位 Region 槽位可能让方块被赋予错误世界坐标。

**问题分析**：
正常存档不会触发，但一旦 NBT 坐标与 Region 槽位矛盾，继续渲染会产生难以从画面识别的位置错误。

**建议修改**：
当 NBT 提供 `xPos/zPos` 时要求两者同时存在并与 Region 槽位完全一致，否则抛出专用解析错误。

**影响范围**：
现代根级与旧版 `Level` 坐标元数据，以及损坏或手工修改的 Region 文件。

- **解决日期**：2026-08-01
- **实际修改**：新增 `CHUNK_COORDINATE_MISMATCH`，在解析方块前验证存储坐标。
- **验证证据**：新增坐标矛盾单元测试；27 次真实区块逐方块对照中的存储坐标均与 Region 槽位一致。
