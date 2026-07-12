# 代码审查：main.fxml

- **审查日期**：2026-07-11
- **审查工具**：Codex
- **审查范围**：主界面 FXML 布局文件，定义左右分栏的存档列表和详情面板
- **问题总数**：4 个（🔴 0 / 🟠 0 / 🟡 0 / 🟢 4）


---

### ISSUE-FXML-001：ListView 导入但未使用（死代码）

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/resources/fxml/main.fxml`
- **行号**：第 4 行
- **状态**：已修复

**问题描述**：
FXML 文件导入了 `javafx.scene.control.ListView`，但布局中实际使用的是 `TreeView`（第 29 行）。

**当前代码**：
```xml
<?import javafx.scene.control.ListView?>      <!-- 第 4 行，未使用 -->
<!-- ... -->
<javafx.scene.control.TreeView fx:id="worldTreeView" ... />  <!-- 第 29 行，实际使用 -->
```

**问题分析**：
- 与 ISSUE-LISTCELL-003 同源：项目早期可能使用 ListView，后来改为 TreeView
- 未使用的导入不会导致错误，但增加了阅读时的噪音
- 同时也说明 `WorldListCell.java` 是死代码

**建议修改**：
```xml
<!-- 删除第 4 行 -->
<!-- <?import javafx.scene.control.ListView?> -->
```

**影响范围**：
- 仅影响代码整洁度，不影响功能


---

### ISSUE-FXML-002：窗口尺寸 900×600 硬编码 【连带问题】

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/resources/fxml/main.fxml` ← → `App.java`
- **行号**：第 15 行
- **状态**：已修复
- **连带来源**：ISSUE-APP-003

**问题描述**：
FXML 中的 `prefHeight="600.0" prefWidth="900.0"` 与 App.java 中的 `new Scene(root, 900, 600)` 重复定义了相同的尺寸。

**当前代码**：
```xml
<!-- main.fxml 第 15 行 -->
<SplitPane ... prefHeight="600.0" prefWidth="900.0" ...>

<!-- App.java 第 20 行 -->
Scene scene = new Scene(root, 900, 600);
```

**问题分析**：
- 窗口尺寸在两个地方定义：FXML 和 App.java
- 如果只修改其中一处，另一处不会同步
- FXML 中的 `prefHeight/prefWidth` 是组件的建议尺寸，App.java 中的 `Scene` 构造参数是窗口尺寸
- 两处都硬编码了 900×600，维护时容易遗漏

**建议修改**：
```xml
<!-- 方案一：FXML 中不指定尺寸，由 App.java 的 Scene 控制 -->
<SplitPane dividerPositions="0.3" ...>
<!-- 删除 prefHeight 和 prefWidth -->

<!-- 方案二：两处都提取为常量（需要改动 App.java） -->
```

**影响范围**：
- 修改窗口尺寸时需要同步修改两处
- 与 ISSUE-APP-003 联动


---

### ISSUE-FXML-003：CSS 样式内联，未使用外部样式表

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/resources/fxml/main.fxml`
- **行号**：第 21、38、52-65 行
- **状态**：已修复

**问题描述**：
多处 CSS 样式直接写在 FXML 的 `style` 属性中，未使用外部 CSS 文件。

**当前代码**：
```xml
<!-- 第 21 行：顶部工具栏背景 -->
<HBox ... style="-fx-background-color: #f4f4f4; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;">

<!-- 第 38 行：世界名称字体 -->
<Label ... style="-fx-font-size: 28px; -fx-font-weight: bold;" ...>

<!-- 第 52-65 行：详情标签样式 -->
<Label style="-fx-font-weight: bold; -fx-text-fill: #555555;" text="Version:" />
<Label ... style="-fx-font-size: 14px;" text="-" />
```

**问题分析**：
- 样式和结构混在一起，修改样式需要改 FXML 文件
- 相同样式（如 `-fx-font-size: 14px`）重复出现 5 次
- JavaFX 支持外部 CSS 文件，可以统一管理样式
- 与 ISSUE-TREECELL-003 同源：Java 代码中也有内联样式

**建议修改**：
```xml
<!-- 1. 创建 src/main/resources/css/styles.css -->
<!-- 2. 在 App.java 中加载 -->
scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());

<!-- 3. FXML 中使用样式类 -->
<HBox styleClass="toolbar">
<Label styleClass="world-name" ...>
<Label styleClass="detail-label" text="Version:" />
<Label styleClass="detail-value" ...>
```

**影响范围**：
- 仅影响代码可维护性，不影响功能
- 与 ISSUE-TREECELL-003 同源


---

### ISSUE-FXML-004：中文文本硬编码在 FXML 中

- **严重程度**：🟢 低
- **类别**：代码质量
- **文件**：`src/main/resources/fxml/main.fxml`
- **行号**：第 26 行
- **状态**：已修复

**问题描述**：
按钮文本 `"选择存档目录..."` 直接硬编码在 FXML 中，如果未来需要支持多语言，需要修改 FXML 文件。

**当前代码**：
```xml
<Button mnemonicParsing="false" onAction="#handleChooseFolder" text="选择存档目录..." />
```

**问题分析**：
- 中文文本硬编码在布局文件中
- 如果未来需要支持英文界面，需要修改 FXML 或使用资源绑定
- JavaFX 支持 `%key` 语法从 `ResourceBundle` 加载文本

**建议修改**：
```xml
<!-- 方案一：使用 ResourceBundle -->
<!-- 在 FXML 根元素添加 -->
<SplitPane ... fx:controller="..." resources="messages">

<!-- 按钮使用 %key -->
<Button text="%button.chooseFolder" ...>

<!-- 方案二：保持中文硬编码（MVP 阶段足够） -->
```

**影响范围**：
- 仅影响国际化支持，MVP 阶段问题不大

## 归档解决记录

- **解决日期**：2026-07-11
- **验证证据**：FXML 加载和 JavaFX 启动验证通过；资源路径、控制器字段和样式表绑定复核通过。

| 问题 | 实际修改 |
|---|---|
| ISSUE-FXML-001 | 删除未使用的 `ListView` 导入。 |
| ISSUE-FXML-002 | 移除 FXML 中与应用入口重复的窗口尺寸配置。 |
| ISSUE-FXML-003 | 将可复用样式迁移至 `styles.css`。 |
| ISSUE-FXML-004 | 将目录选择按钮文本迁移至资源文件。 |
