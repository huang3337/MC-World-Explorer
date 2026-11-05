# 项目结构

正式文档统一从 [docs 目录](docs/README.md)进入。

| 位置 | 内容 |
|---|---|
| `src/main/java` | 正式应用、地图、资源解析、插件宿主与三维窗口 |
| `src/main/resources` | FXML、CSS、着色器、标准资源协议数据和界面文本 |
| `src/test/java` | 自动回归与显式启用的原生/真实数据验收 |
| `tools/` | 可复用 Windows 打包工具与现有发行图标/使用说明 |
| `gradle/`、`build.gradle` | Gradle Wrapper、Java 21 构建与本体隔离门禁 |
| `docs/` | 使用、扩展、版本、决策和审查分类导航 |

主要 Java 模块：`world/nbt/region` 读取存档；`preview/map` 构建二维预览与地图；`storage` 管理便携数据；`ui` 管理主界面；`viewer/voxel` 管理有限三维场景、资源和渲染。`experimental/v04/v05` 仍有独立验证启动入口，不是专用 Mod 插件，也不由正式主界面调用。

本地输出不上传：`build/core-0.7.4` 为当前编译与测试，`build/windows-package` 为唯一 Windows 候选，`build/release-readiness-0.7.4-20261105` 为精简最终证据。`config/cache/logs/exports` 是用户便携运行数据，`MC` 是本机测试存档，`.gradle` 是依赖缓存。仓库外延期材料不被构建引用。
