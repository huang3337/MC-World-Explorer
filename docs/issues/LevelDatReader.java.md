# 代码审查：LevelDatReader.java

- **审查日期**：2026-06-19
- **审查工具**：Claude Code
- **审查范围**：解析 Minecraft level.dat 文件，提取世界元数据并封装为 WorldInfo 对象
- **问题总数**：5 个（🔴 0 / 🟠 1 / 🟡 4 / 🟢 0）

---

### ISSUE-LEVELDAT-001：三层嵌套 try-catch，异常信息被吞掉

- **严重程度**：🟠 高
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 22-44 行
- **状态**：待修复

**问题描述**：
为尝试三种压缩格式（GZIP → NONE → ZLIB），使用了三层嵌套 try-catch。内层异常被静默吞掉，不记录任何信息，调试时无法知道前两种格式为什么失败。

**当前代码**：
```java
CompoundBinaryTag root = null;
try {
    root = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.GZIP);
} catch (Exception e) {           // ← e 被吞掉，没有记录
    try {
        root = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.NONE);
    } catch (Exception e2) {      // ← e2 被吞掉，没有记录
        try {
            root = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.ZLIB);
        } catch (Exception e3) {  // ← e3 也没有记录，只打印了一句话
            System.err.println("Failed to read level.dat for " + worldFolder);
            // ...
        }
    }
}
```

**问题分析**：
- 三个异常 `e`、`e2`、`e3` 全部被吞掉，没有写入日志
- 如果 level.dat 文件损坏，开发者无法知道是哪种格式的解析出了问题
- 嵌套三层 try-catch 代码难以阅读和维护
- C 语言类比：这就像 `if (try_gzip() != OK) { if (try_none() != OK) { if (try_zlib() != OK) { 报错; } } }`，嵌套过深

**建议修改**：
```java
// 用循环替代嵌套，统一处理异常
private static final BinaryTagIO.Compression[] COMPRESSIONS = {
    BinaryTagIO.Compression.GZIP,
    BinaryTagIO.Compression.NONE,
    BinaryTagIO.Compression.ZLIB
};

CompoundBinaryTag root = null;
for (BinaryTagIO.Compression compression : COMPRESSIONS) {
    try {
        root = BinaryTagIO.reader().read(levelDatPath, compression);
        break; // 成功则跳出
    } catch (Exception e) {
        // 记录每次失败的原因，便于调试
        logger.debug("Failed to read {} with {}: {}",
            levelDatPath, compression, e.getMessage());
    }
}

if (root == null) {
    logger.error("Failed to read level.dat for {} with any compression", worldFolder);
    return createFallback(worldFolder);
}
```

**影响范围**：
- 影响所有存档的解析过程
- 打包后无法追踪解析失败的根本原因
- 代码可维护性差

---

### ISSUE-LEVELDAT-002：错误处理策略不一致

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 15-49 行
- **状态**：待修复

**问题描述**：
同一个方法中存在两种不同的错误处理策略：有时抛出异常，有时返回降级对象，调用方无法统一处理。

**当前代码**：
```java
// 情况 1：level.dat 不存在 → 抛异常
if (!Files.exists(levelDatPath)) {
    throw new IOException("level.dat not found in " + worldFolder);
}

// 情况 2：压缩格式全部失败 → 返回降级对象（不抛异常）
catch (Exception e3) {
    WorldInfo fallback = new WorldInfo(worldFolder);
    fallback.setVersionName("解析失败");
    return fallback;
}

// 情况 3：Data 标签缺失 → 抛异常
if (data == null || data.keySet().isEmpty()) {
    throw new IOException("Invalid level.dat format (missing 'Data' tag)");
}
```

**问题分析**：
- 调用方 `WorldScanner.scanWorlds()` 用 `try-catch` 包裹调用，捕获异常后跳过
- 但情况 2 不会抛异常，返回一个 `versionName = "解析失败"` 的对象
- 调用方会把这个"半残"对象当作正常世界加入列表
- 用户在界面上看到一个名称正常、但所有详情都显示"解析失败"的世界，体验困惑

**建议修改**：
```java
// 统一策略：全部返回正常对象，或全部抛异常
// 推荐：全部返回正常对象，用 WorldInfo 的状态字段标记是否解析成功

public class WorldInfo {
    private boolean parsed = false; // 标记是否成功解析
    // getter/setter
}

// 调用方根据 parsed 字段决定是否显示
```

**影响范围**：
- 影响 `WorldScanner` 和 `MainController` 的错误处理逻辑
- 用户看到"解析失败"的世界混在正常列表中，容易困惑

---

### ISSUE-LEVELDAT-003：System.err.println 打包后丢失

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 31 行
- **状态**：待修复

**问题描述**：
与 ISSUE-APP-001 相同，`System.err.println()` 在打包后输出丢失。

**当前代码**：
```java
System.err.println("Failed to read level.dat for " + worldFolder + " with any compression.");
```

**问题分析**：
- 与 App.java 中的问题相同，属于全局性问题
- 应统一使用日志框架

**建议修改**：
```java
logger.error("Failed to read level.dat for {} with any compression", worldFolder);
```

**影响范围**：
- 与 ISSUE-APP-001 相同，打包后错误信息丢失

---

### ISSUE-LEVELDAT-004：多处 NBT 字段读取缺少 null 检查

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 55-59 行
- **状态**：待修复

**问题描述**：
读取 NBT 字段时，部分字段没有检查返回值是否为 null 或默认值，可能导致 WorldInfo 中存储无意义的数据。

**当前代码**：
```java
info.setLevelName(data.getString("LevelName"));    // 可能返回 null
info.setGameType(data.getInt("GameType"));          // 不存在时返回 0（恰好是 Survival）
info.setHardcore(data.getBoolean("hardcore"));       // 不存在时返回 false
info.setLastPlayed(data.getLong("LastPlayed"));      // 不存在时返回 0
info.setGameTime(data.getLong("Time"));              // 不存在时返回 0
```

**问题分析**：
- `data.getString("LevelName")` 在字段不存在时返回 `null`，会传入 WorldInfo
- `data.getInt("GameType")` 不存在时返回 `0`，恰好是 Survival，不会报错但可能不准确
- 其他字段不存在时返回 `0`，UI 上会显示 `0` 或 `Unknown`
- 问题不严重，因为 Minecraft 的 level.dat 通常包含这些字段
- 但对于损坏的存档或非标准存档，可能出现意外值

**建议修改**：
```java
// 对关键字段做 null/默认值处理
String levelName = data.getString("LevelName");
info.setLevelName(levelName != null ? levelName : worldFolder.getFileName().toString());

int gameType = data.getInt("GameType");
info.setGameType(gameType >= 0 && gameType <= 3 ? gameType : 0);
```

**影响范围**：
- 影响损坏存档或非标准存档的解析结果
- 正常存档不受影响

---

### ISSUE-LEVELDAT-005："解析失败" 硬编码字符串跨文件耦合 【连带问题】

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java` ← → `MainController.java`
- **行号**：第 35 行（LevelDatReader）← → 第 128 行（MainController）
- **状态**：待修复
- **连带方向**：LevelDatReader 设置 → MainController 读取

**问题描述**：
`LevelDatReader` 将解析失败的降级对象的 `versionName` 设为 `"解析失败"`，`MainController` 用硬编码字符串 `"解析失败"` 做判断。两个文件通过魔法字符串耦合，任何一方修改都会导致另一方失效。

**当前代码**：
```java
// LevelDatReader.java 第 35 行
fallback.setVersionName("解析失败");

// MainController.java 第 128 行
if ("解析失败".equals(info.getVersionName())) {
    gameModeLabel.setText("解析失败");
    // ...
}
```

**问题分析**：
- 两个文件共享一个魔法字符串 `"解析失败"`，没有常量定义
- 如果 LevelDatReader 改为 `"Parse Failed"`，MainController 的判断会失效
- 如果 MainController 改为判断 `"解析错误"`，降级对象不会被识别
- 这是典型的"字符串耦合"反模式

**建议修改**：
```java
// 方案一：在 WorldInfo 中定义常量
public class WorldInfo {
    public static final String PARSE_FAILED = "解析失败";
}

// LevelDatReader 使用
fallback.setVersionName(WorldInfo.PARSE_FAILED);

// MainController 使用
if (WorldInfo.PARSE_FAILED.equals(info.getVersionName())) {

// 方案二（推荐）：使用 boolean 标记替代字符串判断
public class WorldInfo {
    private boolean parsed = true;
}

// MainController 使用
if (!info.isParsed()) {
```

**影响范围**：
- 影响 LevelDatReader 和 MainController 两个文件
- 修改任一方的字符串都会导致另一方逻辑失效
