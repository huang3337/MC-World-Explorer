# 代码审查：VanillaTintLayers.java

- **审查日期**：2026-11-25
- **审查范围**：原版世界颜色层已登记构建识别与高版本 `pack_format` 通用回退分组
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 0 / 🟢 1）

### ISSUE-VTINT-001：兼容构建版本硬编码且 `pack_format >= 84` 通用回退未区分粒子水色分组

- **严重程度**：🟢 低
- **类别**：代码质量 / 协议边界一致性
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/VanillaTintLayers.java`
- **行号**：26–51（审查时快照）
- **状态**：已修复

**问题描述**：
1. `hasRegisteredProfile` 在 Java 代码中硬编码 `"26.1"`，未与 `vanilla-tint-layers.json` 的 `compatibleBuilds` 元数据保持单一数据来源。
2. `genericFallback` 对所有未登记客户端固定选用 `"waterBlocks"`（`WATER`）与 `repeatLast = true`，未对 `pack_format >= 84`（26.x 协议）切换为 `"particleWater"`（`WHITE`）与非重复层尾语义。

**当前代码**（审查时快照）：
```java
static boolean hasRegisteredProfile(String version) {
    if (version == null || version.isBlank()) return false;
    if (ClientBlockColors.hasLegacyRegistry(version)) return true;
    if ("26.1".equals(version)) return true;
    return Profiles.DATA.getAsJsonObject("profiles").has(version);
}
```

**问题分析**：
在 `vanilla-tint-layers.json` 的 `compatibleBuilds` 条目中显式声明 `"version": "26.1"` 并由 `Profiles` 统一加载，可避免新增兼容构建时遗漏硬编码分支；同时按 `resourceFormat >= 84` 对齐 `particleWater` 与 `repeatLast = false`，可使未登记新旧版本回退各自符合对应世代的原版着色表结构。

**建议修改**：
在 `vanilla-tint-layers.json` 的 `26.3.compatibleBuilds` 中补充 `"version": "26.1"`，由 `VanillaTintLayers` 自动汇总已登记版本集合，并在 `genericFallback` 中区分 `pack_format >= 84` 的 `particleWater` 与层索引边界。

**影响范围**：
`VanillaTintLayers` 已登记版本识别及未登记客户端的通用原版颜色层回退。

- **解决日期**：2026-11-25
- **实际修改**：更新 `vanilla-tint-layers.json` 与 `VanillaTintLayers.java`，移除硬编码版本号并补齐 `pack_format >= 84` 分支语义。
- **验证证据**：更新 `LegacyClientTintFallbackTest` 覆盖 1.16.5（`pack_format=6`）与未登记 `26.2`（`pack_format=90`）水色与层尾行为，并通过全量离线回归。
