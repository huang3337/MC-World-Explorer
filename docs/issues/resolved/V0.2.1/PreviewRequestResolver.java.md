# 代码审查：PreviewRequestResolver.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：维度中心、默认图层和玩家坐标可信度解析
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

## 当前结论

当前解析器会拒绝非有限或超出整数范围的玩家坐标，并要求玩家维度标签存在且与目标维度匹配。两项问题均已解决；问题代码为 V0.2.1 开发或审查阶段快照，当前实现以实际修改和验证证据为准。

### ISSUE-REQUEST-001：异常浮点玩家坐标直接参与取整

- **严重程度**：🟠 高
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewRequestResolver.java`
- **行号**：68-78
- **状态**：已修复

**问题描述**：
首版仅检查玩家坐标可用标记，未排除 `NaN`、无穷大或超出整数范围的异常 NBT 数值。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
boolean playerInDimension = world.isPlayerPositionAvailable()
        && dimension.id().equals(normalizeId(world.getPlayerDimension()));
```

**问题分析**：
异常坐标在取整和范围计算时可能得到无意义中心或溢出结果，使后台生成失败并影响当前世界预览。

**建议修改**：
```java
Double.isFinite(x) && x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE
```

**影响范围**：
被 Mod 写入异常坐标、损坏 `level.dat` 或人工修改 NBT 的世界。

- **解决日期**：2026-07-24
- **实际修改**：新增三轴有限数和整数范围验证；不可靠坐标不用于维度匹配，回退维度原点或既有主世界中心规则。
- **验证证据**：`PreviewRequestResolverTest.ignoresNonFinitePlayerPosition` 通过。

### ISSUE-REQUEST-002：缺失玩家维度被误判为当前位于主世界

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/preview/PreviewRequestResolver.java`
- **行号**：审查阶段 23-25；当前实现 25、67-82
- **状态**：已修复

**问题描述**：
玩家维度匹配曾直接归一化维度 ID，而归一化函数会把 `null` 和空字符串视为主世界。旧存档或特殊整合包只有玩家坐标、没有可靠维度标签时，程序会错误使用玩家 Y 选择主世界高度带。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
boolean playerInDimension = hasUsablePlayerPosition(world)
        && dimension.id().equals(
                WorldDimension.normalizeId(world.getPlayerDimension()));
```

**问题分析**：
玩家坐标存在并不代表其所属维度可被可靠确认。把缺失维度归一化为主世界会错误改变主世界默认图层；个人重生点的旧格式兼容语义不能直接套用于玩家当前位置。

**建议修改**：
```java
String playerDimension = world.getPlayerDimension();
return hasUsablePlayerPosition(world)
        && playerDimension != null
        && !playerDimension.isBlank()
        && dimension.id().equals(WorldDimension.normalizeId(playerDimension));
```

旧数字维度 `0/-1/1` 仍应继续通过归一化映射兼容。

**影响范围**：
缺少或无法解析玩家维度标签的旧存档、损坏存档和特殊整合包。

- **解决日期**：2026-07-24
- **实际修改**：新增 `playerPositionMatches`，要求玩家位置可靠、维度标签存在且非空，并在归一化后与目标维度一致。控制器的默认滑块位置复用同一规则；旧版数值维度 `0/-1/1` 的映射保持不变。
- **验证证据**：`PreviewRequestResolverTest.doesNotTreatMissingPlayerDimensionAsOverworld` 和 `stillMapsLegacyNumericOverworldDimension` 通过；默认环境与真实目录全量测试均通过。
