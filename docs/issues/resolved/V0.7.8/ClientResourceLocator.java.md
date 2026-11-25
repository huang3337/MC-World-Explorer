# 代码审查：ClientResourceLocator.java

- **审查日期**：2026-11-25
- **审查范围**：1.6 之前存档在手动选择客户端 JAR 路径下的版本有效性判定一致性
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 0 / 🟢 1）

### ISSUE-CLIENTLOC-001：`isValidVersion` 未排除 1.2–1.5 版本导致手动选包提示不一致

- **严重程度**：🟢 低
- **类别**：UI 健壮性 / 错误提示一致性
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/ClientResourceLocator.java`
- **行号**：95–97（审查时快照）
- **状态**：已修复

**问题描述**：
`ClientResourceLocator.locate` 已显式拦截 `PRE_16_RELEASE`（`1.2–1.5`）并返回“1.6 之前旧版存档，三维预览使用简化颜色底座”，但 `isValidVersion` 仍对 `"1.5.2"` 返回 `true`，导致 `MapViewerController` 手动选包分支直接调用 `ClientResourceArchive.open` 并报出“资源文件缺少 version.json”。

**当前代码**（审查时快照）：
```java
public static boolean isValidVersion(String version) {
    return LevelDatReader.LEGACY_PRE_1_9_VERSION.equals(version) || isPathSafeVersion(version);
}
```

**问题分析**：
统一在 `isValidVersion` 中拒绝 `PRE_16_RELEASE`，可让自动查找与界面手动选包均走同一提示语义。

**建议修改**：
在 `isValidVersion` 开头检查 `PRE_16_RELEASE.matcher(version).matches()` 并返回 `false`。

**影响范围**：
1.2–1.5 旧版存档在界面手动选择客户端资源时的状态提示。

- **解决日期**：2026-11-25
- **实际修改**：`ClientResourceLocator.isValidVersion` 排除 `PRE_16_RELEASE` 版本号。
- **验证证据**：`ClientResourceLocatorTest` 全部通过。
