# DECISION-025：0.8.1 物理双模块拆分与无 UI 开发者 SDK 架构

- 日期：2026-11-27；关联议题：GitHub Issue #1；目标：将只读存档解析、二维地图渲染、三维体素网格与导出能力拆分为独立无 UI 核心库（`:mc-world-explorer-sdk`），供桌面端（`:mc-world-explorer-app`）与外部 Java 项目复用。
- 执行记录：[V0.8.1](../progress/V0.8.1.md)。本决定在 [DECISION-024](DECISION-024.md) 的纯本体与只读边界内实施，不恢复已退出的专用 Mod 适配或旧插件生成器。

## 决策内容

将原单模块 Gradle 工程物理拆分为两个子模块：

1. `:mc-world-explorer-sdk`（位于 `sdk/`）：纯 Java 21 核心库，使用 `java-library` 与 `maven-publish` 插件，包含 `world`、`nbt`、`region`、`storage`、`preview`、`map`、非 LWJGL 的 `viewer`、非 LWJGL 的 `voxel`（`data`、`io`、`mesh`、`resource`）以及新增的高层门面与导出包 `com.mcworldexplorer.sdk`。严禁依赖 JavaFX、Ikonli、LWJGL 或绑定特定日志实现（仅依赖 `slf4j-api`、`adventure-nbt`、`gson`、`joml`）。
2. `:mc-world-explorer-app`（位于 `app/`）：桌面应用子模块，使用 `application` 与 `org.openjfx.javafxplugin` 插件，通过 `implementation project(':mc-world-explorer-sdk')` 依赖核心库，仅保留 `Launcher`、`App`、`ClientRuntime`、`WindowsGraphics`、`ui`、`update`、`viewer.lwjgl`、`voxel.render.lwjgl` 及既有隔离验证入口 `experimental`。

## 备选方案

1. **单模块多 JAR 打包（按包过滤生成次级 JAR）**
   - 仅在原单模块 `build.gradle` 中新增自定义 `Jar` 任务排除 `ui` 与 `lwjgl` 包。
   - 缺点：编译期无法阻止核心代码意外引入 JavaFX、LWJGL 或桌面端类型；POM/JitPack 传递依赖元数据仍绑定桌面端依赖，易造成第三方项目依赖污染与版本漂移。
2. **独立仓库或独立分支维护 SDK**
   - 将无 UI 核心复制至外部仓库或长期独立分支发布。
   - 缺点：解析逻辑与桌面端双份维护，每次修复或协议演进均需人工同步，难以保证单一版本号一致。
3. **Gradle 物理双子模块（最终选择）**
   - 同一仓库、统一 `group` 与 `version`，由 `javac` 与 Gradle 依赖图在编译期物理隔离无 UI 核心与桌面端。

## 最终选择

采用 **Gradle 物理双子模块架构（`:mc-world-explorer-sdk` + `:mc-world-explorer-app`）**，并同步提供面向外部 Java 项目的 `com.mcworldexplorer.sdk` 高层门面、无头三维 Wavefront OBJ（含 `.mtl` 与 `0.8.0` 群系着色/双层遮罩烘焙贴图 `_atlas.png`）导出及无头二维 PNG 导出能力。

## 选择原因

1. **编译期物理纯净**：`:mc-world-explorer-sdk` 依赖图不包含 JavaFX、Ikonli、LWJGL 与 `logback-classic`，任何反向引用会在编译阶段立即失败，并辅以字节码级 `verifyCoreIsolation` 与契约测试双重守门。
2. **单源同步演进**：桌面端直接依赖 `:mc-world-explorer-sdk`，所有存档解码、资源解析、群系着色与网格构造成果一次修改、双端同步生效，无版本号或行为漂移。
3. **标准分发兼容**：支持 `publishToMavenLocal`、JitPack（`jitpack.yml`）与 Release 离线构件（主 JAR、`-sources.jar`、`-javadoc.jar`），同时不破坏桌面端 `run-app.bat`、`prepareWindowsClient` 与 `tools/package-windows.ps1` 的打包与校验流程。
4. **保持既有边界**：严格延续 `DECISION-024` 的只读约束与纯本体范围，仅封装通用标准资源解析、纯色简化底座、网格构建与导出能力，不引入专用 Mod 硬编码或旧插件脚手架生成器。

## 长期影响

- 后续所有核心解析、二维地图与三维网格演进均在 `sdk/` 实施，桌面交互、OpenGL 渲染与自动更新在 `app/` 实施。
- `/mcwe-build.properties` 由 `:mc-world-explorer-sdk` 唯一生成，`:mc-world-explorer-app` 通过类路径共享该元数据，避免多模块同名资源冲突。
- 公开面向外部项目的 `com.mcworldexplorer.sdk` API 在后续小版本迭代中需保持向后兼容意识，并在变动时同步更新 `docs/SDK_GUIDE.md` 与契约测试。
