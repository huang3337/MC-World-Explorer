# 代码审查：MainController.java

- **审查日期**：2026-07-24
- **审查工具**：Codex
- **审查范围**：维度/图层选择、后台请求、取消、缓存显示和导出状态
- **问题总数**：3 个（🔴 0 / 🟠 0 / 🟡 3 / 🟢 0）

## 当前结论

当前控制器使用地表按钮和平滑滑块，并按维度独立保存本次运行的选择状态。切换到没有既有状态的维度时会先清空图层控件，任务或图片读取失败会恢复该维度最后一次成功状态，失败后允许直接重试同一图层。三项问题均已解决；问题代码为 V0.2.1 开发或审查阶段快照，当前实现以实际修改和验证证据为准。

### ISSUE-CONTROLLER-012：维度失败后可能重新启用上一维度图层

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：395-402、452-472
- **状态**：已修复

**问题描述**：
切换维度时首版只禁用图层下拉框而不清空旧列表；如果新维度高度探测失败，失败回调会根据旧列表非空重新启用它。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
layerComboBox.setDisable(true);
// Failure handler:
layerComboBox.setDisable(layerComboBox.getItems().isEmpty());
```

**问题分析**：
用户会看到上一维度的高度带出现在当前失败维度中，继续选择还会触发无效请求，造成维度与图层状态不一致。

**建议修改**：
```java
private void clearLayerControls() {
    layerComboBox.getItems().clear();
    layerComboBox.setDisable(true);
}
```

**影响范围**：
无法确定高度范围的 Mod 维度、损坏维度和快速切换场景。

- **解决日期**：2026-07-24
- **实际修改**：原下拉框实现会在新请求前清空并禁用旧列表；后续平滑滑块实现进一步改为按维度保存状态，没有状态或失败时清空当前图层控件。
- **验证证据**：独立维度状态测试、控制器回归测试以及默认和真实目录全量测试通过。

### ISSUE-CONTROLLER-013：图层失败前写入选择状态导致同层无法直接重试

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：审查阶段 493-555；当前实现 441-558
- **状态**：已修复

**问题描述**：
地表按钮和高度滑块曾在后台生成成功前，把目标图层写成该维度的 `selectedLayer`。生成失败后，再次选择同一高度会被相等判断提前返回，用户无法直接重试。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
dimensionPreviewStates.put(dimension, state.withSelectedLayer(surface));
startPreview(previewWorld, dimension, surface, state.sliderY());

DimensionPreviewState updated = new DimensionPreviewState(
        state.heightRange(), sliderY, layer);
dimensionPreviewStates.put(dimension, updated);
startPreview(previewWorld, dimension, layer, sliderY);
```

**问题分析**：
`selectedLayer` 应表示界面最后成功显示的图层。请求发起前覆盖该值，会让失败恢复逻辑把尚未成功的目标当成当前状态；同层短路随后又会阻止用户重新发起请求。

**建议修改**：
```java
// 只保存待选滑块值；图片成功显示后再提交 selectedLayer。
dimensionPreviewStates.put(dimension, state.withSliderY(sliderY));
startPreview(previewWorld, dimension, layer, sliderY);
```

同层短路还必须检查界面是否仍有图片；失败界面图片为空时应允许重试相同图层。

**影响范围**：
地表总览和所有高度带的失败恢复、维度切换后缓存读取失败以及同层重试。

- **解决日期**：2026-07-24
- **实际修改**：`selectedLayer` 只表示最后一次成功显示的图层；滑块操作只保存该维度的待选 Y 值，目标图层仅在后台任务成功且 JavaFX 确认图片可显示后提交。任务失败或图片解码失败都会恢复最后成功状态；“同层无需重复加载”只在图片仍显示时生效，失败界面可直接重试相同图层。
- **验证证据**：`MainControllerTest.keepsLastSuccessfulLayerWhileUpdatingPendingSliderCoordinate` 和 `retriesSelectedLayerWhenFailureClearedTheImage` 通过；默认环境与真实目录全量测试均通过。

### ISSUE-CONTROLLER-014：成功回调中的图片读取失败不会结束进度状态

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：审查阶段 441-465、615-623；当前实现 441-485、618-627
- **状态**：已修复

**问题描述**：
后台任务成功后，如果缓存 PNG 无法被 JavaFX 解码，旧代码会显示失败信息，但不会解除进度绑定和隐藏进度条。

**当前代码**（V0.2.1 开发或审查阶段快照）：
```java
if (showPreviewImage(display.cache().imagePath())) {
    setPreviewReady(...);
} else {
    showPreviewFailure("缓存图片无法读取");
}
```

**问题分析**：
任务成功并不保证 JavaFX 最终能够解码图片。该分支没有调用 `finishPreviewProgress`，会让失败提示与仍处于绑定或显示状态的进度条同时存在，界面状态不一致。

**建议修改**：
```java
private void showPreviewFailure(String detail) {
    finishPreviewProgress();
    // 统一处理其余失败界面状态。
}
```

将进度清理集中在失败显示入口，避免不同失败路径继续遗漏或重复清理。

**影响范围**：
缓存文件被外部删除、损坏、权限变化或 JavaFX 图片解码失败的场景。

- **解决日期**：2026-07-24
- **实际修改**：`showPreviewFailure` 统一调用 `finishPreviewProgress`；任务失败路径删除重复清理调用。成功、失败、取消以及成功后图片解码失败均收敛到一致的进度结束行为；图片解码失败还会恢复最后成功的维度图层状态。
- **验证证据**：逐调用点复核确认所有失败入口均经过统一清理；默认环境与真实目录全量测试均通过。JavaFX 图片解码失败的实际界面路径尚无自动化点击测试，保留为人工验收关注项。
