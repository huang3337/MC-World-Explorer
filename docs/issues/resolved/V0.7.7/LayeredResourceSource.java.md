# 代码审查：LayeredResourceSource.java

- **审查日期**：2026-11-25
- **审查范围**：叠加资源源对基础客户端能力、世界群系输入与来源追踪的委托语义
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-LAYERED-001：叠加资源源未委托基础客户端版本、数据注册表与群系着色输入

- **严重程度**：🟡 中
- **类别**：模块耦合 / 资源契约一致性
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/LayeredResourceSource.java`
- **行号**：21–65（审查时快照）
- **状态**：已修复

**问题描述**：
`LayeredResourceSource` 已聚合 `baseResourceFormat()` 并将 `hasBaseTexture(id)` 委托给底层客户端来源（`sources.getLast()`），但未覆写 `baseClientVersion()`、`baseDataRegistry(String)`、`clientClass(String)`、`worldSeed()`、`biomeBlendRadius()`、`dimensionHeight(ResourceLocation)` 与 `origin(...)`，导致调用方通过 `LayeredResourceSource` 在客户端归档之上叠加资源包时，着色与群系采样链路只能读到接口默认的空值。

**当前代码**（审查时快照）：
```java
@Override public int baseResourceFormat(){
    int format=-1;
    for(var source:sources) {
        int candidate=source.baseResourceFormat();
        if(candidate<0)continue;
        if(format>=0 && format!=candidate)return -1;
        format=candidate;
    }
    return format;
}
@Override public void validate() throws IOException {for(var source:sources)source.validate();}
@Override public boolean hasBaseTexture(ResourceLocation id)throws IOException {
    return sources.getLast().hasBaseTexture(id);
}
```

**问题分析**：
0.7.7 的通用原版世界着色回退及 1.13–1.19.2 维度高度／群系气候回退均依赖 `baseClientVersion()` 与 `baseDataRegistry(...)`；已登记客户端版本的静态验证还依赖 `clientClass(...)`。若 `LayeredResourceSource` 未传递上述方法，叠加资源包会使已核实客户端与旧版客户端回退同时失效。

**建议修改**：
按与 `baseResourceFormat()` 一致的无冲突规则聚合 `baseClientVersion()`（多层声明冲突时返回空），将 `baseDataRegistry`、`worldSeed`、`biomeBlendRadius`、`dimensionHeight` 与受共享 64 MiB 会话预算约束的 `clientClass` 委托给底层 `sources.getLast()`，并在 `read` 命中时记录有界来源说明。

**影响范围**：
`LayeredResourceSource` 叠加场景下的客户端颜色绑定、旧版群系气候／维度高度回退及资源来源追踪。

- **解决日期**：2026-11-25
- **实际修改**：补齐 `LayeredResourceSource` 对基础客户端能力、世界群系输入、受预算约束的 `clientClass` 及有界 `origin` 的委托。
- **验证证据**：新增 `LayeredResourceSourceTest.delegatesBaseClientCapabilitiesAndRejectsConflictingClientVersions` 并通过全量离线回归。
