# 代码审查：SettingsDialog.java

- **审查日期**：2026-11-26
- **审查范围**：「设置 → 常用」导入旧版数据后当前设置面板控件状态与保存逻辑的一致性
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-SETTINGSDIALOG-001：设置窗口内执行旧版数据导入后未回填当前面板控件导致点击「保存」覆盖新导入配置

- **严重程度**：🟠 高
- **类别**：UI 健壮性 / 状态同步
- **文件**：`src/main/java/com/mcworldexplorer/ui/SettingsDialog.java`
- **行号**：131–145、166–179（审查时快照）
- **状态**：已修复

**问题描述**：
在「设置 → 常用」点击「从旧版目录导入数据…」成功后，旧版 `config/settings.properties` 已写入配置目录，但当前打开的 `SettingsDialog` 面板中的下拉框、存档路径与复选框仍保持导入前的旧状态；随后点击底部「保存」按钮会将面板旧值重新写回磁盘，覆盖刚导入的个人设置。

**当前代码**（审查时快照）：
```java
var result = LegacyDataImporter.importFrom(
        chosen.toPath().toAbsolutePath().normalize(), LegacyDataImporter.Options.ALL);
importNote.setText("已导入 " + result.configFilesCopied() + " 项配置与 " + result.cacheFilesCopied() + " 项缓存。");
error.setText("");
onLegacyImported.run();
```

**问题分析**：
`SettingsDialog` 在打开时一次性读取 `PortableSettings` 初始化各表单控件，`apply` 处理器在点击「保存」时直接读取表单控件当前值落盘，因此中途外部导入更新磁盘配置后必须同步刷新表单控件。

**建议修改**：
在 `LegacyDataImporter.importFrom` 成功后立即重新从 `PortableSettings` 读取最大堆、扫描并发、三维缓存档位、深浅外观、自定义存档目录、自动检查更新开关及已匹配显卡型号回填当前对话框控件，并在 `MapViewerController.reloadAfterLegacyImport` 中同步应用运行态偏好。

**影响范围**：
通过「设置 → 常用 → 从旧版目录导入数据…」补导旧版配置并点击「保存」的场景。

- **解决日期**：2026-11-26
- **实际修改**：在 `SettingsDialog` 导入成功回调中重新加载并回填全部表单控件，同时更新 `MapViewerController.reloadAfterLegacyImport` 同步应用并发数、外观与三维缓存档位。
- **验证证据**：全量离线测试编译与回归通过。
