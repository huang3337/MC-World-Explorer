# 代码审查：App.java

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：应用程序入口，负责初始化 JavaFX 窗口并加载主界面
- **问题总数**：3 个（🔴 1 / 🟠 1 / 🟡 0 / 🟢 1）


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
