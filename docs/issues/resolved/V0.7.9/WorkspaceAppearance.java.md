# 代码审查：WorkspaceAppearance.java

- **审查日期**：2026-11-26
- **审查范围**：主窗口 `Scene` 样式表挂载时机与首帧 CSS 变量解析
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-APPEARANCE-001：样式表监听绑定在 `SplitPane` 子项内部节点导致首帧 CSS 递归中途才挂载 `styles.css` 并触发大量变量未解析警告

- **严重程度**：🟡 中
- **类别**：UI 健壮性 / 生命周期时序
- **文件**：`src/main/java/com/mcworldexplorer/ui/WorkspaceAppearance.java`
- **行号**：33–45（审查时快照）
- **状态**：已修复

**问题描述**：
通过 `run-app.bat` 启动程序时，控制台在首帧布局阶段输出近 300 条 `javafx.scene.CssStyleHelper calculateValue` 警告（`Could not resolve '-mcwe-ink'`、`ClassCastException: java.lang.String cannot be cast to javafx.scene.paint.Paint` 等），虽在下一轮脉冲自动恢复正常配色，但造成启动控制台大量告警噪声。

**当前代码**（审查时快照）：
```java
host.sceneProperty().addListener((observable, previous, scene) -> {
    if (scene == null) return;
    if (!scene.getStylesheets().contains(BASE)) scene.getStylesheets().add(BASE);
    apply(scene, dark.isSelected());
    ...
    scene.getRoot().setStyle("-fx-font-family: '" + family + "';");
});
```

**问题分析**：
`MapViewerController.initialize` 传入的 `host` 为 `mapSurface`，其位于 `SplitPane.getItems()` 内部。在 JavaFX 中，`SplitPane` 的 `items` 在 `new Scene(root)` 时并未挂入场景图，直至首帧 `root.applyCss()` 执行到 `SplitPane` 创建 `SplitPaneSkin` 时才动态接入 `Scene`。此时根节点 `appRoot`（`.root`）已在未挂载 `styles.css` 的状态下完成了本轮 `doProcessCSS()`，尚未计算缓存 `.root` 中的 `-mcwe-*` 颜色变量，而随后继续遍历的子树节点立即按刚加入的 `styles.css` 查找 `-mcwe-*` 变量，从而在首帧触发批量解析失败警告。

**建议修改**：
新增 `WorkspaceAppearance.attachSceneHost`，由 `MainController.initialize` 将顶层根节点 `appRoot` 绑定至样式初始化器，使 `new Scene(root)` 构造阶段即提前完成 `styles.css`、`dark.css` 与根字体样式注册（且避免重复写入相同 `style` 字符串），从源头消除中途挂载样式表导致的首帧查找告警。

**影响范围**：
应用启动首帧的 JavaFX CSS 解析与控制台日志整洁度。

- **解决日期**：2026-11-26
- **实际修改**：在 `WorkspaceAppearance` 提取幂等的 `attachSceneHost` 并在 `MainController.initialize` 将 `appRoot` 接入场景监听。
- **验证证据**：`ModalOverlayContractTest.loadingMainFxmlIntoSceneProducesZeroCssLookupWarnings` 拦截 `javafx.scene.CssStyleHelper` 日志验证警告数为 0，实机运行确认告警消失。
