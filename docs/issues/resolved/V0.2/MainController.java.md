# 代码审查：MainController.java

- **审查日期**：2026-07-14
- **审查工具**：Codex
- **审查范围**：世界预览状态、PNG 导出、目录选择和严格便携配置
- **问题总数**：2 个（🔴 0 / 🟠 0 / 🟡 2 / 🟢 0）

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

### ISSUE-CONTROLLER-011：自定义目录仍写入 Windows Preferences

- **严重程度**：🟡 中
- **类别**：模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/ui/MainController.java`
- **行号**：42、151-152、230-241（审查时快照）
- **状态**：已修复

**问题描述**：

控制器继续通过 Java `Preferences` 保存 `custom_saves_path`。Windows 实现会把该值写入注册表，而不是程序根目录，违反 V0.2 已确认的严格便携存储决策。

**当前代码**：

```java
Preferences prefs = Preferences.userNodeForPackage(MainController.class);
String savedPathStr = prefs.get("custom_saves_path", null);

prefs.put("custom_saves_path", selectedDir.getAbsolutePath());
```

**问题分析**：

用户删除便携版目录后，注册表中的自定义路径仍会保留；程序生成的数据位置也不再全部可见。该行为与 `DECISION-001` 中“配置写入程序根目录 `config/`、不使用系统目录保存应用数据”的约束不一致。

**建议修改**：

```java
Path selectedRoot = portableSettings.loadCustomSavesPath()
        .orElseGet(WorldScanner::getDefaultGameRoot);
portableSettings.saveCustomSavesPath(selectedRoot);
```

使用独立的便携配置组件读写 `config/settings.properties`，控制器只处理选择结果和错误状态。

**影响范围**：

影响自定义 Minecraft 目录的持久化位置和便携版清理边界，不影响存档扫描、NBT 解析或世界文件。

- **解决日期**：2026-07-18
- **实际修改**：新增 `PortableSettings`，原子读写程序根目录 `config/settings.properties`；`MainController` 停止使用 `java.util.prefs.Preferences`。配置读取失败时回退默认 Minecraft 目录并显示状态，保存失败时本次扫描继续但明确提示无法持久化。
- **验证证据**：新增缺失配置不创建目录、中文路径往返、原子替换和配置目录冲突测试；源码扫描确认生产代码不再引用 `java.util.prefs` 或 `Preferences`。
