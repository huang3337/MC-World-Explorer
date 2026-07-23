# 代码审查：PortablePaths.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：严格便携目录初始化及其与 Minecraft 世界目录的写入边界
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-PORTABLE-001：程序位于世界目录内时会向存档写入便携数据

- **严重程度**：🟠 高
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/storage/PortablePaths.java`
- **行号**：21-27
- **状态**：已忽略

**问题描述**：
便携目录初始化只验证程序根目录可写，没有判断程序根目录本身是否位于某个 Minecraft 世界目录内。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
static Path initialize(Path applicationRoot) throws IOException {
    Path root = normalized(applicationRoot);
    ensureWritableDirectory(root.resolve(LOGS_DIRECTORY_NAME));
    System.setProperty(APPLICATION_HOME_PROPERTY, root.toString());
    return root;
}
```

**问题分析**：
如果用户把便携版解压到某个世界目录或其子目录，应用启动时就会创建 `logs/`，后续还可能创建 `cache/`、`config/` 和 `exports/`。这直接违反 DECISION-001 和路线图“不向 Minecraft 存档写入任何应用数据”的约束。现有真实目录测试把应用根放在 JUnit 临时目录，因此无法覆盖这种部署位置。

**建议修改**：
```java
Path root = normalized(applicationRoot);
if (isInsideMinecraftWorld(root)) {
    throw new IOException("程序目录不能位于 Minecraft 存档目录内");
}
ensureWritableDirectory(root.resolve(LOGS_DIRECTORY_NAME));
```

启动写入发生前应沿规范化后的程序根祖先检查 `level.dat`，并为“程序根等于世界目录”和“程序根位于世界子目录”补充测试。不得静默改写到 AppData。

**影响范围**：
 日志初始化、缩略图缓存、便携配置和默认导出；仅在便携程序被放入世界目录时触发，但触发后会破坏项目承诺的存档只读边界。

- **确认日期**：2026-07-24
- **开发者结论**：该场景需要把完整便携目录放入世界目录才会发生，不会改写既有 `level.dat` 或 Region 数据。开发者确认不为该低概率部署方式增加启动限制，本版本不修改代码。
- **记录处理**：保留审查提示和风险边界，但不把它计入 V0.2.1 待修问题。
