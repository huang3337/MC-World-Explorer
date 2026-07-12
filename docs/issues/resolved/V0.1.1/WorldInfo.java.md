# 代码审查：WorldInfo.java

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：世界信息数据传输对象（DTO），存储从 level.dat 解析出的所有世界元数据
- **问题总数**：5 个（🔴 0 / 🟠 0 / 🟡 3 / 🟢 2）


---

### ISSUE-WORLDINFO-001：gameType 用 int 表示，缺少枚举定义

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldInfo.java`
- **行号**：第 12 行
- **状态**：已修复

**问题描述**：
游戏模式用裸 `int` 存储，含义靠调用方用 `switch` 硬编码判断，没有类型安全保障。

**当前代码**：
```java
// WorldInfo.java 第 12 行
private int gameType;

// MainController.java 第 137-143 行（调用方）
switch (info.getGameType()) {
    case 0: modeStr = "Survival"; break;
    case 1: modeStr = "Creative"; break;
    case 2: modeStr = "Adventure"; break;
    case 3: modeStr = "Spectator"; break;
}
```

**问题分析**：
- `gameType` 的含义（0=生存, 1=创造, 2=冒险, 3=旁观）是 Minecraft 的约定，但代码中没有任何地方定义这个映射
- 如果传入 `gameType = 99`，不会报错，只是静默显示 "Unknown"
- `switch` 逻辑分散在 `MainController` 中，如果其他地方也需要判断游戏模式，必须重复写 `switch`
- C 语言类比：这就像用 `int` 代替 `enum`，容易传错值

**建议修改**：
```java
// 新增枚举类 GameType.java
public enum GameType {
    SURVIVAL(0, "Survival"),
    CREATIVE(1, "Creative"),
    ADVENTURE(2, "Adventure"),
    SPECTATOR(3, "Spectator");

    private final int id;
    private final String displayName;

    GameType(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static GameType fromId(int id) {
        for (GameType type : values()) {
            if (type.id == id) return type;
        }
        return SURVIVAL;
    }

    public String getDisplayName() { return displayName; }
}

// WorldInfo 中改为
private GameType gameType = GameType.SURVIVAL;

// MainController 中简化为
gameModeLabel.setText(info.getGameType().getDisplayName());
```

**影响范围**：
- 当前不影响功能，但影响代码可维护性
- 后续版本如果多处需要判断游戏模式，问题会放大


---

### ISSUE-WORLDINFO-002：所有 setter 无输入验证

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldInfo.java`
- **行号**：第 26-67 行（所有 setter）
- **状态**：已修复

**问题描述**：
所有 setter 方法直接赋值，不检查输入是否合法，可能导致对象处于无效状态。

**当前代码**：
```java
public void setGameType(int gameType) { this.gameType = gameType; }
public void setLevelName(String levelName) { this.levelName = levelName; }
public void setSpawnX(int spawnX) { this.spawnX = spawnX; }
```

**问题分析**：
- `setLevelName(null)` 会导致 `levelName` 为 null，UI 显示时可能出现空指针
- `setGameType(999)` 会存入一个无效值
- `setLastPlayed(-1)` 会存入一个无意义的时间戳
- 当前数据来源是 `LevelDatReader`，由 NBT 库解析，数据基本可信
- 但作为防御性编程，关键字段应该有基本验证

**建议修改**：
```java
public void setLevelName(String levelName) {
    this.levelName = (levelName != null && !levelName.isEmpty()) ? levelName : "Unknown World";
}

public void setGameType(int gameType) {
    this.gameType = (gameType >= 0 && gameType <= 3) ? gameType : 0;
}
```

**影响范围**：
- 当前数据来源可信，实际触发概率低
- 但如果未来支持用户手动编辑存档信息，问题会暴露


---

### ISSUE-WORLDINFO-003：toString() 遗漏关键字段

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldInfo.java`
- **行号**：第 69-77 行
- **状态**：已修复

**问题描述**：
`toString()` 方法只输出 4 个字段，遗漏了 `folderPath`、`lastPlayed`、`randomSeed`、玩家坐标等关键信息。

**当前代码**：
```java
@Override
public String toString() {
    return "WorldInfo{" +
            "levelName='" + levelName + '\'' +
            ", versionName='" + versionName + '\'' +
            ", gameType=" + gameType +
            ", hardcore=" + hardcore +
            '}';
}
```

**问题分析**：
- `toString()` 主要用于调试和日志输出
- 当前输出缺少 `folderPath`（定位问题时最关键的信息）、`lastPlayed`、`randomSeed`、玩家坐标
- 输出的信息不足以帮助开发者定位问题

**建议修改**：
```java
@Override
public String toString() {
    return "WorldInfo{" +
            "folderPath=" + folderPath +
            ", levelName='" + levelName + '\'' +
            ", versionName='" + versionName + '\'' +
            ", gameType=" + gameType +
            ", hardcore=" + hardcore +
            ", lastPlayed=" + lastPlayed +
            ", randomSeed=" + randomSeed +
            ", playerPos=(" + playerX + ", " + playerY + ", " + playerZ + ")" +
            '}';
}
```

**影响范围**：
- 仅影响调试体验，不影响功能


---

### ISSUE-WORLDINFO-004：多个变量声明在同一行

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/world/WorldInfo.java`
- **行号**：第 17-18 行
- **状态**：已修复

**问题描述**：
多个字段在同一行声明，降低代码可读性，增加 git diff 时的噪音。

**当前代码**：
```java
private int spawnX, spawnY, spawnZ;
private double playerX, playerY, playerZ;
```

**问题分析**：
- 同一行声明多个变量，阅读时不易快速定位某个字段
- git diff 无法精确显示单个字段的修改历史
- Java 编码规范（Google Java Style）建议每行只声明一个变量

**建议修改**：
```java
private int spawnX;
private int spawnY;
private int spawnZ;

private double playerX;
private double playerY;
private double playerZ;
```

**影响范围**：
- 仅影响代码风格和可读性，不影响功能


---

### ISSUE-WORLDINFO-005：null 值从 LevelDatReader 经 WorldScanner 传播到 MainController 【连带问题】

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`WorldInfo.java` ← `LevelDatReader.java` → `WorldScanner.java` → `MainController.java`
- **行号**：第 31 行（WorldInfo）/ 第 55 行（LevelDatReader）/ 第 39 行（WorldScanner）/ 第 125 行（MainController）
- **状态**：已修复
- **连带方向**：LevelDatReader 设置 → WorldInfo 存储 → WorldScanner 传递 → MainController 消费

**问题描述**：
`LevelDatReader` 读取 NBT 字段时可能获得 null，通过无验证的 setter 存入 WorldInfo，经 WorldScanner 传递到 MainController。整个链条中 null 值无人拦截，最终由 UI 层做防御性检查。

**null 值传播链**：
```
LevelDatReader.java:55
    data.getString("LevelName")  → 返回 null
        ↓
WorldInfo.java:31
    setLevelName(null)  → 无验证，直接存储
        ↓
WorldScanner.java:39
    worlds.add(info)  → null 值随对象进入列表
        ↓
MainController.java:125
    info.getLevelName() != null ? ...  → 被迫做防御性检查
```

**问题分析**：
- 职责划分不清：数据生产者（LevelDatReader）不保证数据质量，消费者（MainController）被迫做防御
- 如果 MainController 遗漏了 null 检查，会抛出 NullPointerException
- WorldInfo 作为数据载体，应该保证自己不会持有无效数据

**建议修改**：
```java
// 在 WorldInfo 的 setter 中拦截 null
public void setLevelName(String levelName) {
    this.levelName = (levelName != null && !levelName.isEmpty())
        ? levelName
        : this.folderPath.getFileName().toString();
}
```

**影响范围**：
- 影响 LevelDatReader → WorldInfo → WorldScanner → MainController 整条链路
- 修复 WorldInfo 的 setter 即可切断传播链
