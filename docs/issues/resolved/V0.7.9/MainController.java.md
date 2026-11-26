# 代码审查：MainController.java

- **审查日期**：2026-11-26
- **审查范围**：软件内模态遮罩层的底层键盘/焦点隔离与旧版数据导入后的运行态同步
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-CONTROLLER-017：软件内模态遮罩弹出期间底层工作区未拦截键盘事件且焦点可能停留在背景控件

- **严重程度**：🟠 高
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：156–168、339–344（审查时快照）
- **状态**：已修复

**问题描述**：
`modalOverlay` 仅消费了落在遮罩自身的鼠标点击与滚轮事件，未在 `modalOverlay` 可见时拦截底层 `workspaceSplitPane` 的键盘与输入事件，也未在打开模态卡片时转移焦点，导致遮罩显示期间按 `Tab`、`Space`、`Enter` 等按键可能穿透触发背景工作区控件。

**当前代码**（审查时快照）：
```java
if (modalOverlay != null) {
    modalOverlay.setOnMouseClicked(Event::consume);
    modalOverlay.setOnMousePressed(Event::consume);
    modalOverlay.setOnMouseReleased(Event::consume);
    modalOverlay.setOnScroll(Event::consume);
}
```

**问题分析**：
同场景 `StackPane` 遮罩不会像独立模态 `Stage` 那样自动阻断兄弟子树的焦点遍历链；若不显式拦截底层 `SplitPane` 输入并迁移焦点，键盘操作会绕过模态遮罩。

**建议修改**：
在 `workspaceSplitPane` 上注册事件过滤器，当 `modalOverlay.isVisible()` 为真时消费键盘、鼠标与滚轮事件；在 `showModalCard` 中将焦点移入当前模态卡片（并支持 `Esc` 关闭「关于」卡片或从保留确认卡片返回），在 `closeModalOverlay` 中恢复工作区焦点；同时在 `refreshAfterLegacyImport` 中同步刷新扫描并发与三维缓存档位。

**影响范围**：
「关于」卡片、仓库跳转确认卡片与首次启动导入旧版数据卡片展示期间的键盘与焦点交互。

- **解决日期**：2026-11-26
- **实际修改**：在 `MainController.initialize`、`showModalCard`、`closeModalOverlay` 与 `refreshAfterLegacyImport` 补齐底层输入拦截、焦点迁移、`Esc` 返回/关闭及完整偏好热刷新。
- **验证证据**：全量离线测试与 `ModalOverlayContractTest` 通过。
