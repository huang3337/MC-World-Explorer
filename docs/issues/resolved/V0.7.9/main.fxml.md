# 代码审查：main.fxml

- **审查日期**：2026-11-26
- **审查范围**：主窗口顶栏「关于」按钮与右上角更新小蓝点容器的垂直对齐布局
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-FXML-006：顶栏包裹「关于」按钮的 `StackPane` 未限制最大高度且设为 `TOP_RIGHT` 导致按钮向上偏移不与相邻按钮对齐

- **严重程度**：🟠 高
- **类别**：UI 健壮性
- **文件**：`src/main/resources/fxml/main.fxml`
- **行号**：67–72（审查时快照）
- **状态**：已修复

**问题描述**：
通过 `run-app.bat` 启动后，主窗口顶栏 `.world-header`（`HBox alignment="CENTER_LEFT"`）中的「关于」按钮偏离了左侧「存档详情」「设置」按钮的水平基线而向上贴边。

**当前代码**（审查时快照）：
```xml
<StackPane alignment="TOP_RIGHT">
    <Button fx:id="aboutButton" text="关于" mnemonicParsing="false" minWidth="-Infinity"
            onAction="#handleAbout" styleClass="quiet-button"/>
    <Region fx:id="aboutUpdateBadge" visible="false" managed="false" mouseTransparent="true"
            styleClass="update-badge-dot" StackPane.alignment="TOP_RIGHT"/>
</StackPane>
```

**问题分析**：
`HBox` 默认开启 `fillHeight=true`。普通 `Button` 的 `maxHeight` 默认为 `USE_PREF_SIZE`（`32px`），因此由 `HBox` 垂直居中摆放；而 `StackPane` 的 `maxHeight` 默认为 `Double.MAX_VALUE`，被 `HBox` 拉伸至整个顶栏高度，再叠加 `StackPane` 自身的 `alignment="TOP_RIGHT"`，使内部 `aboutButton` 被吸附到顶栏上边缘而非垂直居中。

**建议修改**：
将包裹容器改为 `<StackPane alignment="CENTER" maxHeight="-Infinity" maxWidth="-Infinity">`，使容器尺寸严格贴合 `aboutButton` 首选宽高并随 `HBox` 垂直居中，同时保留子元素 `aboutUpdateBadge` 上的 `StackPane.alignment="TOP_RIGHT"`。

**影响范围**：
主界面顶栏工具按钮视觉对齐与小蓝点定位。

- **解决日期**：2026-11-26
- **实际修改**：在 `main.fxml` 为包裹 `aboutButton` 的 `StackPane` 设置 `alignment="CENTER" maxHeight="-Infinity" maxWidth="-Infinity"`。
- **验证证据**：`ModalOverlayContractTest.headerAboutButtonWrapperAlignsVerticallyWithSiblingToolbarButtons` 验证 `aboutButton` 与 `settingsButton` 场景纵坐标及高度一致。
