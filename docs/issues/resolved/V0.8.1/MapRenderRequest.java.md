# 代码审查：MapRenderRequest.java

- **审查日期**：2026-11-27
- **审查工具**：Gemini
- **审查范围**：二维与三维选区构建请求的极端负坐标减法溢出与异常类型一致性
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-MAPREQ-001：极端负方块坐标在计算采样区块下界时发生 `long` 减法静默下溢或抛出未封装的 `ArithmeticException`

- **严重程度**：🟡 中
- **类别**：安全性 / 边界校验
- **文件**：`sdk/src/main/java/com/mcworldexplorer/sdk/MapRenderRequest.java`（含 `SceneBuildRequest.java` 同类转换）
- **行号**：54–65（审查时快照）
- **状态**：已修复

**问题描述**：
`MapRenderRequest` 在计算采样区块起点时直接执行 `builder.minBlockX - builder.blocksPerPixel`，当传入接近 `Long.MIN_VALUE` 的坐标时会发生静默 `long` 下溢；此外超出 32 位区块整数范围的坐标迟至 `McWorld.renderMapImage`（及 `SceneBuildRequest.builderForBlockRange`）的 `Math.toIntExact` 才抛出 `ArithmeticException`，与 SDK 其余参数校验统一抛出 `IllegalArgumentException` 的契约不一致。

**当前代码**（审查时快照）：
```java
long minChunkX = Math.floorDiv(builder.minBlockX - builder.blocksPerPixel, 16L);
long maxChunkX = Math.floorDiv(builder.maxBlockX, 16L);
```

**问题分析**：
公共 SDK 请求对象应在构造阶段一次性完成精确算术与 `int` 区块边界校验，将所有坐标越界统一转化为语义明确的 `IllegalArgumentException`。

**建议修改**：
在 `MapRenderRequest` 构造器中使用 `Math.subtractExact` 与 `Math.toIntExact` 预解析并缓存 `minChunkX/maxChunkX/minChunkZ/maxChunkZ`，捕获 `ArithmeticException` 统一封装为 `IllegalArgumentException`；在 `SceneBuildRequest.builderForBlockRange` 同步封装 `Math.toIntExact` 溢出。

**影响范围**：
`MapRenderRequest` 与 `SceneBuildRequest` 的极端坐标输入校验。

- **解决日期**：2026-11-27
- **实际修改**：在请求构造阶段通过 `Math.subtractExact` 与 `Math.toIntExact` 完成区块边界校验并统一抛出 `IllegalArgumentException`。
- **验证证据**：`WorldExplorerSdkTest.requestsValidateBoundsAndRespectCancellation` 验证通过。
