# 代码审查：App.java

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：应用程序入口，负责初始化 JavaFX 窗口并加载主界面
- **问题总数**：5 个（🔴 1 / 🟠 3 / 🟡 0 / 🟢 1）


---

### ISSUE-APP-001：缺少日志框架，错误信息打包后丢失

- **严重程度**：🔴 严重
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/App.java`
- **行号**：全局（影响所有使用 `System.err.println` 的文件）
- **状态**：已修复

**问题描述**：
项目中所有错误信息通过 `System.err.println()` 输出到标准错误流。当程序打包成可执行文件后，控制台不可见，所有错误信息将丢失，用户和开发者均无法获取任何错误反馈。

**当前代码**：
```java
// App.java 第 17 行
throw new IllegalStateException("Cannot find /fxml/main.fxml");

// LevelDatReader.java 第 31 行
System.err.println("Failed to read level.dat for " + worldFolder + " with any compression.");

// WorldScanner.java 第 42 行
System.err.println("Failed to read world at " + entry + ": " + e.getMessage());
```

**问题分析**：
- `System.err.println()` 输出到标准错误流，在 IDE 开发环境中可在控制台看到
- 打包成 `.exe` 后，没有控制台窗口，输出无处可去
- `throw new IllegalStateException()` 会弹出 JavaFX 默认错误框，显示原始堆栈信息，用户体验极差
- 开发者无法获取用户环境下的错误信息，难以排查问题

**建议修改**：
```java
// build.gradle 添加依赖
implementation 'ch.qos.logback:logback-classic:1.4.14'

// 使用 SLF4J + Logback
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger logger = LoggerFactory.getLogger(App.class);

// 替换所有 System.err.println 为 logger 调用
logger.error("Cannot find /fxml/main.fxml");
logger.error("Failed to read level.dat for {}", worldFolder, e);
```

**影响范围**：
- 影响所有文件中的错误输出（App.java、LevelDatReader.java、WorldScanner.java）
- 打包后程序的可调试性为零
- 用户遇到问题时无法提供有效反馈


---

### ISSUE-APP-002：缺少全局异常捕获机制

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/App.java`
- **行号**：第 13-24 行（`start()` 方法）
- **状态**：已修复

**问题描述**：
未捕获的异常会导致程序直接崩溃，没有记录日志，也没有友好的用户提示。

**当前代码**：
```java
@Override
public void start(Stage stage) throws Exception {
    // 如果这里抛出异常，程序直接崩溃
    URL fxmlLocation = getClass().getResource("/fxml/main.fxml");
    if (fxmlLocation == null) {
        throw new IllegalStateException("Cannot find /fxml/main.fxml");
    }
    // ...
}
```

**问题分析**：
- JavaFX 框架对未捕获异常有内置处理：FX 线程的异常会弹出错误对话框，但显示原始堆栈信息
- 非 FX 线程（如后台任务）的异常会被静默吞掉，不记录、不提示
- 当前代码中 `throws Exception` 直接向上抛出，没有在 `start()` 内部做兜底处理
- 程序启动失败时，用户只看到一个通用错误框，无法理解发生了什么

**建议修改**：
```java
// 在 App.java 的 main() 方法中添加全局异常处理器
public static void main(String[] args) {
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
        logger.error("Uncaught exception in thread {}", thread.getName(), throwable);
    });
    launch(args);
}
```

**影响范围**：
- 后台线程中的未捕获异常会被静默吞掉
- 启动失败时用户看到原始堆栈信息，体验差
- 无法追踪程序崩溃的根本原因


---

### ISSUE-APP-003：窗口尺寸 900×600 硬编码

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/java/com/mcworldexplorer/App.java`
- **行号**：第 20 行
- **状态**：已修复

**问题描述**：
窗口宽度和高度直接以魔法数字写死在代码中，修改时需要找到这一行并改源码。

**当前代码**：
```java
Scene scene = new Scene(root, 900, 600);
```

**问题分析**：
- `900` 和 `600` 是魔法数字，没有说明含义
- 如果需要调整窗口尺寸，必须修改源代码并重新编译
- 多处引用相同尺寸时，容易出现不一致
- 严重程度低，因为 MVP 阶段窗口尺寸基本固定

**建议修改**：
```java
private static final int DEFAULT_WIDTH = 900;
private static final int DEFAULT_HEIGHT = 600;

// 使用
Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
```

**影响范围**：
- 仅影响代码可读性和可维护性
- 不影响功能和用户体验

### ISSUE-APP-004：jpackage classpath 启动器无法直接启动 JavaFX Application 主类

- **严重程度**：🟠 高
- **类别**：错误处理
- **文件**：`src/main/java/com/mcworldexplorer/App.java`、`src/main/java/com/mcworldexplorer/Launcher.java`、`build.gradle`
- **行号**：`App.java` 第 14 行；`Launcher.java` 第 1-10 行；`build.gradle` 第 26-28 行
- **状态**：已修复

**问题描述**：
jpackage 首次生成的 EXE 启动后立即退出。直接使用 classpath 启动时报告“缺少 JavaFX 运行时组件”，尽管 JavaFX 依赖已经包含在应用目录中。

**当前代码**：

以下为审查时快照：
```java
public class App extends Application {
    public static void main(String[] args) {
        launch(args);
    }
}
```

**问题分析**：
Java 启动器会对直接继承 `Application` 的主类进行特殊检测，classpath 形式的 jpackage 启动会在应用代码执行前失败。

**建议修改**：
```java
public final class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
```

**影响范围**：
- Windows x64 便携版 EXE
- Gradle application 主入口

**解决记录（2026-07-13）**：新增普通 Java `Launcher` 主类并将 Gradle、jpackage 入口统一指向该类。该修改使 jpackage 能进入应用主类；后续可视化复验又暴露并解决了独立的精简运行时模块问题 `ISSUE-APP-005`。

### ISSUE-APP-005：jpackage 精简运行时遗漏应用启动所需的标准模块

- **严重程度**：🟠 高
- **类别**：构建与发布
- **文件**：`packaging/build-portable.ps1`
- **行号**：jpackage `--add-modules` 参数
- **状态**：已修复

**问题描述**：
便携版最初只加入了直接判断所需的少量 JDK 模块。实际双击 EXE 后先后出现 `javax/naming/NamingException` 和 `javax/script/Bindings` 缺失，GUI 启动器只显示笼统的“Failed to launch JVM”。

**问题分析**：
Logback 会在初始化时使用 `java.naming`，JavaFX FXML 会在加载主界面时使用 `java.scripting`。这些依赖通过框架内部和反射路径触发，普通编译与单元测试不会验证 jlink 精简运行时是否完整。早期验收只检查进程是否存活，而错误对话框本身也会保持进程响应，因此曾被错误判断为启动成功。

**建议修改**：
```powershell
--add-modules "java.se,jdk.unsupported"
```

**影响范围**：
- Windows x64 便携版内置运行时
- EXE 启动、日志初始化和 FXML 主界面加载

**解决记录（2026-07-13）**：将精简模块列表改为标准 `java.se` 模块集合并保留 `jdk.unsupported`，重新生成 app-image 和 ZIP。控制台诊断版持续运行且无启动异常；正式 EXE 可视化验收显示完整主界面，并只读发现 25 个世界。

## 归档解决记录

- **解决日期**：ISSUE-APP-001 至 003 于 2026-07-11 完成；ISSUE-APP-004 至 005 于 2026-07-13 完成。
- **验证证据**：Gradle `clean test` 通过；控制台诊断版无启动异常；正式便携版可视化确认主界面正常显示并发现 25 个世界。

| 问题 | 实际修改 |
|---|---|
| ISSUE-APP-001 | 引入 SLF4J + Logback，用结构化日志替代标准错误输出。 |
| ISSUE-APP-002 | 注册全局未捕获异常处理器并记录异常堆栈。 |
| ISSUE-APP-003 | 将窗口宽高提取为应用常量并统一使用。 |
| ISSUE-APP-004 | 使用普通 Java Launcher 转交 JavaFX 启动，修复 jpackage EXE 立即退出。 |
| ISSUE-APP-005 | 使用 `java.se` 补齐框架隐式依赖，并以主窗口可见作为便携版启动验收标准。 |
