# 代码审查：LegacyFlatTextureModels.java

- **审查日期**：2026-11-25
- **审查范围**：1.6.1–1.7.10 平面纹理静态模型生成与简单 Mod 方块无歧义判定
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-FLATMODEL-001：流体解析强转 `BuiltinBlockModels` 且简单 Mod 单贴图判定未排除多面伴随贴图

- **严重程度**：🟠 高
- **类别**：安全性 / 模块耦合
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/LegacyFlatTextureModels.java`
- **行号**：40–67（审查时快照）
- **状态**：已修复

**问题描述**：
1. 流体分支直接将 `archive` 强制转换为 `BuiltinBlockModels`，若调用方传入未包装的 `BlockResourceSource` 会抛出 `ClassCastException`。
2. `resolveSimpleModBlock` 只要检测到 `blocks/<path>.png` 存在即生成单贴图六面体，未排查 `_top`、`_bottom`、`_side`、`_front` 等多面伴随贴图，容易把多面机器方块误绘为同贴图立方体。

**当前代码**（审查时快照）：
```java
if (name.equals("water") || name.equals("lava") || name.equals("bubble_column")) {
    Optional<List<Part>> fluid = ((BuiltinBlockModels) archive).resolve(state);
    fluid.ifPresent(parts -> recordTextures(parts, sources));
    return fluid;
}
...
ResourceLocation candidate = new ResourceLocation(id.namespace(), "blocks/" + id.path());
if (archive.read(ResourceLocation.Kind.TEXTURE, candidate).isEmpty()) {
    return Optional.empty();
}
```

**问题分析**：
平面纹理模式规定仅对无未知多面歧义的单一同名 Mod 贴图做六面体近似；存在伴随方向贴图的机器或柱状方块应保持降级并输出原因。同时工具方法不应假设外层必定已完成 `BuiltinBlockModels` 包装。

**建议修改**：
使用 `instanceof BuiltinBlockModels` 安全获取或按需构造包装器；在 `resolveSimpleModBlock` 中检查 `_top`、`_bottom`、`_side`、`_front` 伴随贴图，任一存在即返回 `Optional.empty()` 走简化颜色降级。

**影响范围**：
1.6.1–1.7.10 流体静态几何解析及实例 Mod 方块近似映射边界。

- **解决日期**：2026-11-25
- **实际修改**：替换强转为安全 `instanceof` 分支，并在 `resolveSimpleModBlock` 中增加四类多面伴随贴图排除检查。
- **验证证据**：`PreFlatteningResourcePipelineTest.opensLegacy1710ArchiveScansVersionedModsAndSynthesizesFlatTextures` 新增含 `crusher_front.png` 的多面 Mod 方块降级断言并通过回归。
