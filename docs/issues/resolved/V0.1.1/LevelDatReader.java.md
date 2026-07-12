# 代码审查：LevelDatReader.java

- **审查日期**：2026-07-12
- **审查工具**：Codex
- **审查范围**：解析 Minecraft level.dat 文件，提取世界元数据并封装为 WorldInfo 对象
- **问题总数**：6 个（🔴 0 / 🟠 2 / 🟡 4 / 🟢 0）




---

### ISSUE-LEVELDAT-002：错误处理策略不一致

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 15-49 行
- **状态**：已修复

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
- **状态**：已修复

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

### ISSUE-LEVELDAT-005："解析失败" 硬编码字符串跨文件耦合 【连带问题】

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java` ← → `MainController.java`
- **行号**：第 35 行（LevelDatReader）← → 第 128 行（MainController）
- **状态**：已修复
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


---

### ISSUE-LEVELDAT-001：三层嵌套 try-catch，异常信息被吞掉

- **严重程度**：🟠 高
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 22-44 行
- **状态**：已修复

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

**解决记录（2026-07-11）**：已使用固定压缩格式数组和循环替代三层嵌套 `try-catch`。每次失败均写入 debug 日志，全部失败后记录 warn 日志并返回未解析对象。

---

### ISSUE-LEVELDAT-004：多处 NBT 字段读取缺少 null 检查

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：第 55-59 行
- **状态**：已修复

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

**解决记录（2026-07-11）**：原记录将风险主要描述为 `null` 传播并不完整；当前 NBT API 对缺失字段还可能表现为类型默认值。现已在读取基础信息和出生点前检查键是否存在，由 `WorldInfo` 保留明确、安全的默认状态。

---

### ISSUE-LEVELDAT-006：默认 NBT 读取额度导致大型整合包存档无法识别

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/nbt/LevelDatReader.java`
- **行号**：压缩格式读取循环
- **状态**：已修复

**问题描述**：
`LevelDatReader` 使用 `BinaryTagIO.reader()` 的默认读取额度。整合包世界的 `level.dat` 解压后通常远大于原版小型存档，超过默认额度时三种压缩尝试均失败，最终对象被标记为未解析并由 `WorldScanner` 过滤。

**样本证据**：
- 只读检查目录：`D:\MC\.minecraft\versions`
- 版本/整合包目录：19 个
- 包含 `saves` 的目录：17 个
- 包含有效 `level.dat` 的世界：24 个，分布在 15 个实例中
- 所有 24 个文件均具有 GZIP 文件头，并可完整解压
- 当前可识别样本：2 个，解压后约 4.8 KiB 和 25 KiB
- 当前失败样本：22 个，解压后约 138 KiB 至 6.3 MiB

**当前代码**：
```java
root = BinaryTagIO.reader().read(levelDatPath, compression);
```

**问题分析**：
- 目录发现逻辑已经找到 `versions/<实例>/saves/<世界>/level.dat`，主要故障不在目录扫描。
- 日志最终显示的 `incorrect header check` 来自最后一次 ZLIB 尝试，掩盖了首次 GZIP 读取超过 NBT 额度的真实原因。
- 使用无限 Reader 会削弱损坏或恶意 NBT 文件的内存安全边界。
- 当前最大样本解压后约 6.3 MiB，16 MiB 上限能够覆盖现有样本并保留明确边界。

**建议修改**：
```java
private static final long MAX_LEVEL_DAT_BYTES = 16L * 1024 * 1024;
private static final BinaryTagIO.Reader LEVEL_DAT_READER =
        BinaryTagIO.reader(MAX_LEVEL_DAT_BYTES);
```

所有压缩格式共用 `LEVEL_DAT_READER`，不使用 `BinaryTagIO.unlimitedReader()`。

**安全约束**：
- 禁止修改、复制、移动、重命名或删除真实存档文件。
- 真实目录验收仅允许只读打开 `level.dat`。
- 自动化测试中的大型 NBT 文件必须写入 JUnit 临时目录。
- 不递归解析 `region`、`playerdata` 或其他后续版本数据。

**验收标准**：
- 新增超过默认读取额度的大型 GZIP NBT 回归测试。
- `gradlew.bat clean test` 全部通过。
- 对 24 个真实 `level.dat` 执行只读解析，成功数达到 24/24。
- 真实存档目录中不产生任何新文件，文件大小和最后写入时间保持不变。

**影响范围**：
- 影响使用大型或包含大量 Mod 元数据的 Minecraft 世界。
- 修复范围属于 V0.1 存档信息解析兼容性，不涉及 V0.2 地图或 Region 解析。

**解决记录（2026-07-12）**：
- 新增 16 MiB 有界 `BinaryTagIO.Reader`，三种压缩格式统一复用。
- 新增 512 KiB 大型 NBT 成功测试和超过 16 MiB 拒绝测试。
- 默认 `clean test` 通过。
- 对 `D:\\MC\\.minecraft\\versions` 下 24 个真实 `level.dat` 完成只读集成验收，解析成功 24/24。
- 验收测试比较了解析前后的文件大小与最后写入时间，结果完全一致。

## 归档解决记录

- **解决日期**：ISSUE-LEVELDAT-001 至 005 于 2026-07-11 完成；ISSUE-LEVELDAT-006 于 2026-07-12 完成。
- **验证证据**：解析器单元测试通过；512 KiB 级大型 NBT 成功；超过 16 MiB 的输入被拒绝；真实目录测试 24/24 通过且文件元数据未变化。

| 问题 | 实际修改 |
|---|---|
| ISSUE-LEVELDAT-001 | 将压缩格式读取改为循环尝试，并逐次保留失败原因。 |
| ISSUE-LEVELDAT-002 | 统一格式解析失败的降级对象语义，保留文件前置 I/O 错误为 `IOException`。 |
| ISSUE-LEVELDAT-003 | 使用日志框架记录解析失败信息。 |
| ISSUE-LEVELDAT-004 | 读取关键 NBT 字段前显式检查键是否存在，避免混淆缺失值与合法默认值。 |
| ISSUE-LEVELDAT-005 | 使用 `parsed` 状态表达解析结果，删除跨模块硬编码字符串判断。 |
| ISSUE-LEVELDAT-006 | 使用 16 MiB 有界 Reader 覆盖大型整合包 NBT，同时保留内存安全上限。 |
