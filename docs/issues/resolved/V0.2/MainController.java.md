# 代码审查：MainController.java

- **审查日期**：2026-07-14
- **审查工具**：Codex
- **审查范围**：世界预览缓存命中后的状态文字显示
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-CONTROLLER-010：缓存命中后中央占位文字覆盖缩略图

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：318-321、398-402（审查时快照）
- **状态**：已修复

**问题描述**：

缓存图片成功加载后，预览上方和图片中央同时显示“已加载缓存 · 中心 X, Z”。中央重复文字遮挡地形，影响缩略图观感。

**当前代码**：

```java
if (reusable.isPresent() && showPreviewImage(reusable.orElseThrow().imagePath())) {
    setPreviewIdle(String.format(
            "已加载缓存 · 中心 %d, %d",
            center.x(),
            center.z()));
    return;
}

private void setPreviewIdle(String status) {
    finishPreviewProgress();
    previewStatusLabel.setText(status);
    previewPlaceholderLabel.setText(status);
    previewPlaceholderLabel.setVisible(true);
}
```

**问题分析**：

`showPreviewImage()` 已经在加载有效图片后隐藏中央占位标签，但缓存命中路径随后调用 `setPreviewIdle()`，又把同一标签设为可见。新生成图片路径没有调用该方法，因此问题只在缓存复用时出现。

**建议修改**：

```java
if (reusable.isPresent() && showPreviewImage(reusable.orElseThrow().imagePath())) {
    setPreviewReady(cacheStatus);
    return;
}
```

成功状态统一隐藏中央占位标签；未选择、生成中和失败状态继续显示中央提示。

**影响范围**：

仅影响 V0.2 世界预览缓存命中后的显示状态，不影响图片生成、缓存失效、中心坐标、存档读取或上方状态文字。

- **解决日期**：2026-07-14
- **实际修改**：新增成功显示状态方法 `setPreviewReady()`，缓存加载和新图生成成功后统一结束进度、更新上方状态并隐藏中央占位标签；保留原有空闲、生成和失败提示。
- **验证证据**：新增 `hidesPreviewPlaceholderWhenImageIsPresent()` 回归测试；默认 69 个测试中 63 个通过、6 个真实目录测试按设计跳过；启用真实目录后 69 个测试全部通过，0 个失败、0 个错误。
