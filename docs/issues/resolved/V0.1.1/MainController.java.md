# 代码审查：MainController.java

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：主界面控制器，负责存档列表加载、选中事件处理、详情面板显示
- **问题总数**：7 个（🔴 0 / 🟠 0 / 🟡 5 / 🟢 2）



---

### ISSUE-CONTROLLER-002：gameType switch 硬编码映射 【连带问题】

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java` ← → `WorldInfo.java`
- **行号**：第 137-143 行
- **状态**：已修复
- **连带来源**：ISSUE-WORLDINFO-001

**问题描述**：
`switch` 语句将 gameType 的 int 值硬编码映射为字符串，与 ISSUE-WORLDINFO-001 同源。游戏模式的定义分散在多个文件中。

**当前代码**：
```java
String modeStr = "Unknown";
switch (info.getGameType()) {
    case 0: modeStr = "Survival"; break;
    case 1: modeStr = "Creative"; break;
    case 2: modeStr = "Adventure"; break;
    case 3: modeStr = "Spectator"; break;
}
```

**问题分析**：
- 游戏模式的映射关系（0=生存, 1=创造...）是 Minecraft 的约定，但代码中没有统一定义
- 如果 WorldInfo 中使用了枚举（ISSUE-WORLDINFO-001 的建议），这里可以简化为一行
- 当前的 `switch` 必须与 LevelDatReader 中写入的值保持一致，维护成本高

**建议修改**：
```java
// 如果 ISSUE-WORLDINFO-001 实现了 GameType 枚举：
gameModeLabel.setText(info.getGameType().getDisplayName());
```

**影响范围**：
- 与 ISSUE-WORLDINFO-001 联动，需同步修改



---

### ISSUE-CONTROLLER-003："解析失败" 硬编码字符串判断 【连带问题】

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java` ← → `LevelDatReader.java`
- **行号**：第 128-134 行
- **状态**：已修复
- **连带来源**：ISSUE-LEVELDAT-005

**问题描述**：
用硬编码字符串 `"解析失败"` 判断是否为降级对象，与 LevelDatReader 中设置的字符串耦合。

**当前代码**：
```java
if ("解析失败".equals(info.getVersionName())) {
    gameModeLabel.setText("解析失败");
    lastPlayedLabel.setText("解析失败");
    seedLabel.setText("解析失败");
    playerPosLabel.setText("解析失败");
    return;
}
```

**问题分析**：
- 字符串 `"解析失败"` 在 LevelDatReader 中设置，在 MainController 中判断，两处必须完全一致
- 如果任何一方修改了这个字符串，逻辑就会失效
- 应该使用 boolean 标记或常量替代魔法字符串

**建议修改**：
```java
// 如果 ISSUE-LEVELDAT-005 实现了 boolean 标记：
if (!info.isParsed()) {
    gameModeLabel.setText("解析失败");
    // ...
    return;
}
```

**影响范围**：
- 与 ISSUE-LEVELDAT-005 联动，需同步修改



---

### ISSUE-CONTROLLER-004：SimpleDateFormat 每次调用都新建实例

- **严重程度**：🟡 中
- **类别**：性能
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：第 151 行
- **状态**：已修复

**问题描述**：
每次调用 `showWorldDetails()` 都创建一个新的 `SimpleDateFormat` 对象，浪费内存。

**当前代码**：
```java
if (info.getLastPlayed() > 0) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    lastPlayedLabel.setText(sdf.format(new Date(info.getLastPlayed())));
}
```

**问题分析**：
- `SimpleDateFormat` 的创建开销相对较大
- 用户每次点击不同的世界都会触发 `showWorldDetails()`，频繁创建对象
- 格式字符串 `"yyyy-MM-dd HH:mm:ss"` 是固定的，不需要每次都指定

**建议修改**：
```java
// 提取为类级别的常量
private static final SimpleDateFormat DATE_FORMAT =
    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

// 使用
if (info.getLastPlayed() > 0) {
    lastPlayedLabel.setText(DATE_FORMAT.format(new Date(info.getLastPlayed())));
}
```

**影响范围**：
- 影响频繁点击存档时的内存分配
- 严重程度不高，但修复成本极低



---

### ISSUE-CONTROLLER-005：seed 为 0 时误显示 "Unknown"

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：第 157 行
- **状态**：已修复

**问题描述**：
当 seed 值为 0 时，界面显示 "Unknown"，但 seed=0 是一个合法的种子值。

**当前代码**：
```java
seedLabel.setText(info.getRandomSeed() != 0 ? String.valueOf(info.getRandomSeed()) : "Unknown");
```

**问题分析**：
- Minecraft 的种子值可以是任意 long 类型，包括 0
- 当用户的世界种子恰好是 0 时，界面会错误地显示 "Unknown"
- 应该区分"种子值为 0"和"种子值未设置"两种情况

**建议修改**：
```java
// 方案一：用特殊值标记"未设置"
// WorldInfo 中定义
public static final long SEED_NOT_SET = Long.MIN_VALUE;

// MainController 中
if (info.getRandomSeed() != WorldInfo.SEED_NOT_SET) {
    seedLabel.setText(String.valueOf(info.getRandomSeed()));
} else {
    seedLabel.setText("Unknown");
}

// 方案二：用 boolean 标记
if (info.isSeedAvailable()) {
    seedLabel.setText(String.valueOf(info.getRandomSeed()));
} else {
    seedLabel.setText("Unknown");
}
```

**影响范围**：
- 影响种子值恰好为 0 的世界的显示
- 概率极低，但逻辑上是 bug



---

### ISSUE-CONTROLLER-006：玩家坐标永远显示，无"无数据"状态

- **严重程度**：🟢 低
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：第 159-160 行
- **状态**：已修复

**问题描述**：
无论是否有玩家坐标数据，都显示格式化后的坐标。当没有数据时显示 `(0.0, 0.0, 0.0)`。

**当前代码**：
```java
String pos = String.format("%.1f, %.1f, %.1f", info.getPlayerX(), info.getPlayerY(), info.getPlayerZ());
playerPosLabel.setText(pos);
```

**问题分析**：
- `WorldInfo` 中 playerX/Y/Z 默认值为 0.0
- 如果存档中没有玩家数据（如服务端存档），会显示 `(0.0, 0.0, 0.0)`
- 用户无法区分"玩家在 (0, 0, 0)"和"没有玩家数据"

**建议修改**：
```java
// 在 WorldInfo 中添加标记
private boolean hasPlayerData = false;

// MainController 中
if (info.hasPlayerData()) {
    String pos = String.format("%.1f, %.1f, %.1f",
        info.getPlayerX(), info.getPlayerY(), info.getPlayerZ());
    playerPosLabel.setText(pos);
} else {
    playerPosLabel.setText("Unknown");
}
```

**影响范围**：
- 影响无玩家数据的存档的显示
- 严重程度低，因为大多数单机存档都有玩家数据



---

### ISSUE-CONTROLLER-007：UI 字符串硬编码，未提取为常量

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：多处
- **状态**：已修复

**问题描述**：
多处 UI 显示的字符串直接硬编码在代码中，未提取为常量或资源文件。

**当前代码**：
```java
worldNameLabel.setText("Select a World");      // 第 164 行
versionLabel.setText("-");                      // 第 165 行
gameModeLabel.setText("-");                     // 第 166 行
gameModeLabel.setText("解析失败");              // 第 129 行
lastPlayedLabel.setText("Unknown");             // 第 154 行
```

**问题分析**：
- 字符串分散在多个方法中，修改时需要找到所有出现位置
- 如果未来需要支持多语言（i18n），需要逐一替换
- MVP 阶段问题不大，但后续国际化需求会暴露

**建议修改**：
```java
// 提取为常量
private static final String NO_SELECTION = "Select a World";
private static final String UNKNOWN = "Unknown";
private static final String NOT_AVAILABLE = "-";
private static final String PARSE_FAILED = "解析失败";
```

**影响范围**：
- 仅影响代码可维护性，不影响功能


---

### ISSUE-CONTROLLER-001：TreeView\<Object\> 类型过宽，导致运行时类型检查

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：第 26 行、第 56 行
- **状态**：已修复

**问题描述**：
`TreeView<Object>` 使用了最宽泛的类型，树节点中混入了 `String`（分组名）和 `WorldInfo`（世界数据）两种类型，导致选中事件处理时必须用 `instanceof` 做运行时类型判断。

**当前代码**：
```java
// 第 26 行
@FXML
private TreeView<Object> worldTreeView;

// 第 56 行
if (newValue != null && newValue.getValue() instanceof WorldInfo) {
    showWorldDetails((WorldInfo) newValue.getValue());
} else {
    clearDetails();
}
```

**问题分析**：
- `TreeView<Object>` 意味着任何对象都可以放进树中，没有类型安全
- `instanceof` 检查是运行时的类型判断，如果传入了意外类型，不会在编译期报错
- Java 中泛型的作用就是在编译期捕获类型错误，这里没有利用到

**建议修改**：
```java
// 方案一：使用自定义的树节点类型
public class TreeNodeData {
    private final String groupName;     // 分组名
    private final WorldInfo worldInfo;  // 世界数据（可为 null）

    public boolean isGroup() { return worldInfo == null; }
}

@FXML
private TreeView<TreeNodeData> worldTreeView;

// 方案二（简单）：保持 Object，但用枚举标记类型
```

**影响范围**：
- 不影响功能，但降低了代码的类型安全性
- 如果后续增加新的节点类型，`instanceof` 判断会越来越多

**解决记录（2026-07-11）**：原题目中的“原始类型”术语不准确，`TreeView<Object>` 是使用过宽泛的类型参数，并非 Java raw type。现已新增 `WorldTreeNode`，将控制器和单元格统一为 `TreeView<WorldTreeNode>`，消除 `Object` 混装和 `instanceof`。

## 归档解决记录

- **解决日期**：2026-07-11
- **验证证据**：Gradle `clean test` 通过；`GameTypeTest`、`WorldInfoTest` 和 `WorldTreeNodeTest` 通过；控制器与 FXML 绑定复核通过。

| 问题 | 实际修改 |
|---|---|
| ISSUE-CONTROLLER-001 | 新增类型化 `WorldTreeNode`，同步修改 TreeView 和 TreeCell。 |
| ISSUE-CONTROLLER-002 | 使用 `GameType` 的显示名称替代控制器内 switch。 |
| ISSUE-CONTROLLER-003 | 使用 `isParsed()` 判断解析结果，删除字符串耦合。 |
| ISSUE-CONTROLLER-004 | 复用日期格式化器，避免每次刷新重复创建。 |
| ISSUE-CONTROLLER-005 | 通过显式 seed 可用状态区分合法的 0 与缺失值。 |
| ISSUE-CONTROLLER-006 | 增加玩家坐标可用状态，无数据时显示统一占位文本。 |
| ISSUE-CONTROLLER-007 | 提取和复用界面状态文本常量。 |
