# 项目结构

正式文档统一从 [docs 目录](docs/README.md)进入。自 `0.8.1` 起工程采用 Gradle 物理双子模块结构（详见 [DECISION-025](docs/decisions/DECISION-025.md)）。

| 位置 | 内容 |
|---|---|
| `sdk/src/main/java` | `:mc-world-explorer-sdk` 无 UI 核心库：`sdk` 高层门面与 OBJ/PNG 导出器、`world`/`nbt`/`region` 存档读取、`storage` 便携存储、`preview`/`map` 二维地图、非 LWJGL 的 `viewer` 与 `voxel/{data,io,mesh,resource}` |
| `sdk/src/main/resources` | 标准资源协议数据（`model-protocols/`、`vanilla-protocol/`）与构建元数据 `/mcwe-build.properties` |
| `sdk/src/test/java` | SDK 门面、OBJ 烘焙导出、零 UI/GL 纯净度契约与核心解析自动回归测试 |
| `app/src/main/java` | `:mc-world-explorer-app` 桌面应用：`Launcher`、`App`、`ClientRuntime`、`WindowsGraphics`、`ui` 主界面、`update` 双源检查更新、`viewer/lwjgl`、`voxel/render/lwjgl` 及隔离验证入口 `experimental` |
| `app/src/main/resources` | FXML、CSS、OpenGL 着色器（`v04/`、`v05/`）、界面文本、许可证与图标资源 |
| `app/src/test/java` | JavaFX 界面、更新检查、LWJGL 渲染与历史验证测试 |
| `tools/` | Windows 打包工具、发行图标与使用说明，以及导入原版方块默认属性的脚本 |
| `gradle/`、`settings.gradle`、`build.gradle`、`jitpack.yml` | Gradle Wrapper、Java 21 双模块构建、JitPack SDK 构建配置与本体隔离/SDK 纯净度门禁 |
| `docs/` | 使用指南、[SDK 使用指南](docs/SDK_GUIDE.md)、扩展、版本、决策和审查分类导航 |

`experimental/v04/v05` 仍保留在 `app/` 下的独立验证启动入口，不是专用 Mod 插件，也不由正式主界面调用。

Gradle 编译目录由 `build.gradle` 指定（现行输出树位于 `build/core-0.8.1/`，SDK 分发汇总位于 `build/core-0.8.1/sdk-dist/`）。Windows 打包默认输出到 `build/windows-package/`，用法见[发布说明](docs/RELEASING.md)。`build/` 下的测量记录和测试应用均为本地产物。`config/`、`cache/`、`logs/` 和 `exports/` 保存便携运行数据，`MC/` 存放本机测试存档。
