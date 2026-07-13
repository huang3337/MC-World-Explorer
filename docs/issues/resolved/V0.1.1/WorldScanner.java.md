# 代码审查：WorldScanner.java

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：扫描 Minecraft 存档目录，支持默认存档和版本隔离存档的发现
- **问题总数**：6 个（🔴 0 / 🟠 2 / 🟡 4 / 🟢 0）



---

### ISSUE-SCANNER-003：System.err.println 打包后丢失

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldScanner.java`
- **行号**：第 42、48、85 行
- **状态**：已修复
- **连带问题**：与 ISSUE-APP-001、ISSUE-LEVELDAT-003 同源

**问题描述**：
与之前发现的问题相同，`System.err.println()` 在打包后输出丢失。

**当前代码**：
```java
// 第 42 行
System.err.println("Failed to read world at " + entry + ": " + e.getMessage());

// 第 48 行
System.err.println("Error while scanning directory " + savesDir + ": " + e.getMessage());

// 第 85 行
System.err.println("Failed to scan versions dir: " + e.getMessage());
```

**问题分析**：
- 图形应用打包运行时不保证用户能够看到标准错误输出。
- 直接打印字符串没有日志级别、时间和异常上下文，不利于定位具体存档或扫描目录。
- 该问题与应用入口和解析器中的直接标准错误输出属于同一类基础设施缺失。

**建议修改**：
```java
logger.warn("Failed to read world at {}: {}", entry, e.getMessage());
logger.error("Error while scanning directory {}: {}", savesDir, e.getMessage());
logger.error("Failed to scan versions dir: {}", e.getMessage());
```

**影响范围**：
- 与 ISSUE-APP-001 相同，打包后错误信息丢失



---

### ISSUE-SCANNER-004：与 LevelDatReader 的降级对象混入正常列表 【连带问题】

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldScanner.java` ← → `LevelDatReader.java`
- **行号**：第 37-43 行
- **状态**：已修复
- **连带来源**：ISSUE-LEVELDAT-002

**问题描述**：
`LevelDatReader` 在解析失败时返回一个 `versionName = "解析失败"` 的降级 WorldInfo 对象。`WorldScanner` 无法区分这个对象是正常解析结果还是降级对象，会将其混入正常列表。

**调用链分析**：
```
WorldScanner.scanWorlds()
    → LevelDatReader.readLevelDat(entry)
        → 压缩格式全部失败
        → 返回 fallback WorldInfo (versionName = "解析失败")
    ← info = fallback 对象
    → worlds.add(info)  ← 降级对象被当作正常世界加入列表
```

**当前代码**：
```java
try {
    WorldInfo info = LevelDatReader.readLevelDat(entry);  // 可能返回降级对象
    worlds.add(info);  // 无条件加入列表
} catch (Exception e) {
    System.err.println("Failed to read world at " + entry);
}
```

**问题分析**：
- `LevelDatReader` 的降级策略是"返回一个标记为解析失败的对象，不抛异常"
- `WorldScanner` 的策略是"捕获异常后跳过"
- 两者策略冲突：降级对象不会触发 catch，直接进入列表
- 最终用户在界面上看到一个"看起来正常但所有详情都显示解析失败"的世界

**建议修改**：
```java
// 方案一：在 WorldScanner 中检查降级标记
WorldInfo info = LevelDatReader.readLevelDat(entry);
if (!"解析失败".equals(info.getVersionName())) {
    worlds.add(info);
}

// 方案二（推荐）：在 WorldInfo 中添加 parsed 状态字段
WorldInfo info = LevelDatReader.readLevelDat(entry);
if (info.isParsed()) {
    worlds.add(info);
}
```

**影响范围**：
- 用户在存档列表中看到"解析失败"的世界，体验困惑
- 这是 LevelDatReader 和 WorldScanner 两个模块之间的接口契约不清晰导致的



---

### ISSUE-SCANNER-005：与 LevelDatReader 的错误处理策略冲突 【连带问题】

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldScanner.java` ← → `LevelDatReader.java`
- **行号**：第 37-43 行
- **状态**：已修复
- **连带来源**：ISSUE-LEVELDAT-002

**问题描述**：
`LevelDatReader` 对同一类错误（解析失败）使用了两种不同的处理方式（抛异常和返回降级对象），导致 `WorldScanner` 的 `catch` 块无法统一处理。

**当前代码**：

以下为审查时流程快照：
```java
WorldInfo info = levelDatReader.readWorldInfo(entry);
worlds.add(info); // 未判断返回对象是否完成解析
```

**错误处理流程对比**：
```
LevelDatReader 的三种失败情况：

情况 1: level.dat 不存在
  → throw IOException
  → WorldScanner catch 捕获 ✓ 跳过

情况 2: Data 标签缺失
  → throw IOException
  → WorldScanner catch 捕获 ✓ 跳过

情况 3: 压缩格式全部失败
  → return fallback WorldInfo（不抛异常）
  → WorldScanner catch 不触发 ✗ 降级对象混入列表
```

**问题分析**：
- `WorldScanner` 假设 `LevelDatReader` 失败时会抛异常
- 但 `LevelDatReader` 在情况 3 下返回降级对象，打破了这个假设
- 两个模块之间的错误处理契约不一致

**建议修改**：
```java
// 统一策略：LevelDatReader 解析失败时全部抛异常
// WorldScanner 统一在 catch 中处理

// LevelDatReader 修改：
if (root == null) {
    throw new IOException("Failed to read level.dat with any compression for " + worldFolder);
}

// WorldScanner 保持不变，catch 统一处理
```

**影响范围**：
- 影响 LevelDatReader 和 WorldScanner 两个模块的接口设计
- 需要同步修改两个文件


---

### ISSUE-SCANNER-001：catch (Exception e) 捕获范围过宽

- **严重程度**：🟡 中
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldScanner.java`
- **行号**：第 40 行
- **状态**：已修复

**问题描述**：
使用 `catch (Exception e)` 捕获所有异常，包括本不应被捕获的运行时异常（如 NullPointerException、ArrayIndexOutOfBoundsException）。

**当前代码**：
```java
try {
    WorldInfo info = LevelDatReader.readLevelDat(entry);
    worlds.add(info);
} catch (Exception e) {
    // 跳过损坏存档，但也跳过了程序 bug 导致的异常
    System.err.println("Failed to read world at " + entry + ": " + e.getMessage());
}
```

**问题分析**：
- `LevelDatReader.readLevelDat()` 声明抛出 `IOException`
- 但 `catch (Exception e)` 会捕获所有异常，包括 `NullPointerException`、`ArrayIndexOutOfBoundsException` 等
- 如果 `LevelDatReader` 内部有 bug 导致 NPE，这里会静默跳过，开发者完全不知道
- 应该只捕获预期的异常类型

**建议修改**：
```java
try {
    WorldInfo info = LevelDatReader.readLevelDat(entry);
    worlds.add(info);
} catch (IOException e) {
    // 只捕获预期的 IO 异常
    logger.warn("Failed to read world at {}: {}", entry, e.getMessage());
}
// 其他异常（如 NPE）会自然抛出，暴露 bug
```

**影响范围**：
- 可能掩盖 LevelDatReader 中的程序 bug
- 导致损坏存档"无声消失"，用户不知道为什么少了一个世界

**解决记录（2026-07-11）**：原记录中的代码片段是首次审查快照；复核时日志和 parsed 过滤已经存在，但宽泛捕获仍未解决。现已仅捕获 `IOException`，运行时编程错误将交由全局异常处理器记录。

---

### ISSUE-SCANNER-002：getDefaultGameRoot() 返回的路径可能不存在

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldScanner.java`
- **行号**：第 96-119 行
- **状态**：已修复

**问题描述**：
方法返回一个 Path 对象，但不检查该路径是否真实存在。调用方拿到一个不存在的路径，需要自行处理。

**当前代码**：
```java
public static Path getDefaultGameRoot() {
    // ...
    if (os.contains("win")) {
        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            return Paths.get(appdata, ".minecraft");  // 不检查是否存在
        }
    }
    // ...
}
```

**问题分析**：
- 用户可能没有安装 Minecraft，`.minecraft` 目录不存在
- 方法返回一个不存在的 Path，调用方 `MainController.loadWorlds()` 需要检查
- 当前 `MainController` 第 79 行做了 `Files.exists(rootPath)` 检查，所以不会崩溃
- 但方法的语义不清晰：返回 Path 是"推荐路径"还是"确认存在的路径"？

**建议修改**：
```java
// 方案一：在方法内部检查，不存在返回 null
public static Path getDefaultGameRoot() {
    Path candidate = findCandidatePath();
    if (candidate != null && Files.exists(candidate) && Files.isDirectory(candidate)) {
        return candidate;
    }
    return null;
}

// 方案二：保持现状，但在 Javadoc 中明确说明
/**
 * Returns the default Minecraft game root directory path.
 * Note: The returned path may not exist if Minecraft is not installed.
 * Callers must check Files.exists() before using.
 */
```

**影响范围**：
- 当前调用方已做检查，不会崩溃
- 但方法契约不清晰，未来调用方可能遗漏检查

**解决记录（2026-07-11）**：复核确认当前调用方已有存在性检查，实际问题是方法契约不清晰，而不是现有功能错误。现已补充 Javadoc，明确返回的是可能不存在的候选路径，调用方必须验证后再扫描。

### ISSUE-SCANNER-006：选择目录类型与扫描入口契约不一致

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldScanner.java`、`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：`WorldScanner.java` 第 61-183 行；`MainController.java` 第 90-136 行
- **状态**：已修复

**问题描述**：
界面允许用户选择 Minecraft 目录，原控制器却始终将该路径当作游戏根目录。直接选择 `saves`、`versions`、单个实例或世界目录时会返回空结果。

**当前代码**：

以下为审查时快照：
```java
groupedWorlds = WorldScanner.scanGameRoot(rootPath);
```

**问题分析**：
入口契约与用户操作不一致，会让结构合法的原版或整合包存档表现为“无法识别”。

**建议修改**：
```java
Map<String, List<WorldInfo>> worlds = WorldScanner.scanSelectedPath(selectedPath);
```

**影响范围**：
- 原版游戏根目录与直接 `saves` 目录
- `versions` 目录和单个整合包实例
- 单个世界目录

**解决记录（2026-07-13）**：新增统一的 `scanSelectedPath()` 入口，按实际目录结构识别单个世界、直接 saves、游戏根目录、versions 集合和实例根目录，并复用原有只读解析逻辑。

## 归档解决记录

- **解决日期**：ISSUE-SCANNER-001 至 005 于 2026-07-11 完成；ISSUE-SCANNER-006 于 2026-07-13 完成。
- **验证证据**：`WorldScannerTest` 覆盖五种目录结构；真实 `versions` 集成测试发现全部 25 个世界；最终 `clean test` 通过。

| 问题 | 实际修改 |
|---|---|
| ISSUE-SCANNER-001 | 将世界读取失败捕获范围收窄为预期的 `IOException`。 |
| ISSUE-SCANNER-002 | 保持候选路径返回行为，并在 Javadoc 中明确调用方必须检查路径。 |
| ISSUE-SCANNER-003 | 使用分级日志替代 `System.err.println`。 |
| ISSUE-SCANNER-004 | 只把 `parsed == true` 的世界加入扫描结果。 |
| ISSUE-SCANNER-005 | 与解析器统一失败对象契约，避免降级对象混入正常列表。 |
| ISSUE-SCANNER-006 | 新增统一路径识别入口，支持游戏根目录、saves、versions、实例和单个世界。 |
