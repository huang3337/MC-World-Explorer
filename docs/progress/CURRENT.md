# 当前状态

更新于 2026-11-27。本页记录 Java 主线的源码、发布、验证与下一步。分轮实测见[0.8.1 记录](V0.8.1.md)、[0.8.0 记录](V0.8.0.md)、[0.7.9 记录](V0.7.9.md)、[0.7.8 记录](V0.7.8.md)、[0.7.7 记录](V0.7.7.md)、[0.7.6 记录](V0.7.6.md)与[0.7.5 记录](V0.7.5.md)，归档问题见[审查索引](../issues/ISSUE_INDEX.md)。

| 项目 | 状态 |
|---|---|
| 当前源码 | **0.8.1**，Java 主线 `main` 与开发分支 `dev`（关联 GitHub Issue #1），标签 `v0.8.1`。采用 Gradle 物理双子模块（`:mc-world-explorer-sdk` + `:mc-world-explorer-app`，[DECISION-025](../decisions/DECISION-025.md)），构建输出位于 `build/core-0.8.1/`：SDK JAR（`mc-world-explorer-sdk-0.8.1.jar`，SHA-256 `59ddb4878e3b2153836ad346bd356515ab7d90a70b675cf5fa6f3d8d00444182`）与应用主 JAR（`MC-World-Explorer-0.8.1.jar`，SHA-256 `c34d34fa557361b5840a45d296881456a680448d7de74a06b8228cdb52593d04`）。新增无 UI 开发者 SDK（`com.mcworldexplorer.sdk`），支持只读存档读取、无客户端纯色三维场景与 `.obj`+`.mtl` 导出、带客户端资源 `0.8.0` 逐顶点群系着色/双层遮罩（含旋转与高分辨率遮罩）Porter-Duff 烘焙 `.obj`+`.mtl`+`_atlas.png` 导出、无头二维 PNG 导出与 GNU GPL v3.0 Maven / JitPack 发布。[0.8.1 验证记录](V0.8.1.md) · [SDK 使用指南](../SDK_GUIDE.md) |
| 最新正式发行 | **0.8.1**，Java 主线 `main`，标签 `v0.8.1`。Windows x64 便携包 `MC-World-Explorer-0.8.1-windows-x64.zip`（43,683,919 字节，SHA-256 `4ca31718e4479c725f53d032a2c3e2404cf9739c093d48037cfcea9f551727f6`）与独立 SDK 构件（`mc-world-explorer-sdk-0.8.1.jar` / `-sources.jar` / `-javadoc.jar`），合并交付 0.8.0 GPU 顶点群系着色与双层遮罩合成管线升级及 0.8.1 无 UI 开发者 SDK。[GitHub Release](https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.8.1) · [Gitee Release](https://gitee.com/huang3337/MC-World-Explorer/releases/latest) · [0.8.1 版本说明](../releases/V0.8.1.md) · [包指纹与核验](V0.8.1.md#windows-package) |
| 范围 | 只读浏览已保存区块。1.13+ 存档支持二维地图与标准三维资源解析（含 1.13–1.19.2 旧版群系与近似着色回退，0.8.0 起采用 GPU 顶点群系着色与双层遮罩合成）；1.6.1–1.12.2 预扁平化存档支持二维完整地图、旧版客户端/实例只读三维纹理（1.8–1.12.2 JSON/Forge V1 与 1.6.1–1.7.10 原版静态几何贴图）及缺失资源时简化颜色底座回退；1.2.1–1.5.2 存档使用三维简化颜色底座。设置页可调整二维渲染、JVM 最大堆、扫描并发与三维缓存。[设置说明](../FAQ.md#render-settings) · [资源解析范围](../MODEL_PROTOCOL_CAPABILITIES.md) |

## 已验证

- **物理双模块拆分与无 UI 开发者 SDK（0.8.1）。** 覆盖 `:mc-world-explorer-sdk` 与 `:mc-world-explorer-app` 物理拆分、`/mcwe-build.properties` 单一元数据归属、`WorldExplorerSdk` / `McWorld` / `SceneBuildRequest` / `MapRenderRequest` / `WavefrontObjExporter` / `ObjExportResult` 全链路回归（合成 Anvil 存档只读校验、无客户端纯色 `.obj`+`.mtl` 导出、带群系着色与高分辨率/旋转双层遮罩 Porter-Duff 烘焙 `_atlas.png` 与翻转 OBJ V 轴校验、临时文件原子替换与取消清理、无头二维 PNG 导出、符号链接与路径穿越防护、极端负坐标越界拦截）、`SdkPurityContractTest` 零桌面端/OpenGL 类路径与字节码契约及 GPL v3.0 POM 许可证校验、Windows x64 便携包 `jpackage` 构建与 6 项代码审查修复归档。离线回归 175 套共 811 项中 **702** 项通过（SDK 578 + App 124）、**109** 项按条件跳过（SDK 61 + App 48）、0 失败；`javadoc`、`sourcesJar`、`publishToMavenLocal`、`assembleSdkDist`、`prepareWindowsClient` 与 `verifyCoreIsolation` 全部通过。[0.8.1 验证记录](V0.8.1.md#regression) · [包指纹与核验](V0.8.1.md#windows-package)

- **GPU 顶点群系着色与双层共面遮罩管线（0.8.0）。** 覆盖 13 浮点统一顶点布局与 `voxel.vert`/`voxel.frag` Porter-Duff `source-over` 双层合成、`TextureAtlas` 去群系染色化与容量上限提升（`2048` 贴图 / `4096×4096`）、`BlockTintPreparation` 稀疏区段调色板解耦与已绑定动态索引预登记、`neoforge:composite` 跨 `render_type` 共面双层合并、`FluidGeometry` 水流染色保留、`FaceMaterialTest` 静态自发光一次合成、`UNBOUND_TINT`/`TINT_UNRESOLVED` 诊断区分，以及 `VertexBiomeTintAtlasScaleTest` 800 种群系 / 500+ 渐变 RGB 零 `TEXTURE_FALLBACK` 压力回归与 4 项代码审查修复归档。默认离线回归 172 套共 801 项中 **692** 项通过、**109** 项按条件跳过、0 失败，`verifyCoreIsolation` 通过；显式启用 `MCWE_R2_NATIVE_TEST=1` 的隐藏窗口 OpenGL 3.3 `TranslucentRenderingTest`（含跨区块半透明排序与双层遮罩单次片元合成像素读回）全部通过。[0.8.0 验证记录](V0.8.0.md#regression)

- **双源检查更新、软件内模态遮罩与旧版数据导入（0.7.9）。** 覆盖 Gitee 直连优先与 GitHub Web JSON 自动静默回退（含无 `html_url` 真实字段解析、`404`/空响应/超时/超限/深嵌套回退、直连与系统代理双路容错及域名白名单校验，实机联调成功识别线上 `v0.7.5`）、`LegacyDataImporter` 只读复制合并及同目录/嵌套/符号链接拦截、`PortableSettings` 首启判定、顶栏「关于」按钮垂直居中对齐、首帧零 `CssStyleHelper` 警告、「关于」卡片双仓库 `/releases/latest` 常驻展示与醒目提示横幅生命周期，以及 `main.fxml`/`styles.css`/`tools/package-windows.ps1`（`jdk.crypto.ec`）契约校验。默认离线回归共 795 项中 **687** 项通过、**108** 项按条件跳过、0 失败，`verifyCoreIsolation` 通过。[0.7.9 验证记录](V0.7.9.md#regression)

- **预扁平化三维纹理适配（1.6.1–1.12.2）。** 0.7.8 覆盖 1.6.1–1.12.2 及 `Anvil (<1.9)` 客户端 JAR 自动匹配/绑定、`LEGACY_JSON_MODEL`（1.8–1.12.2）与 `LEGACY_FLAT_TEXTURE`（1.6.1–1.7.10）只读归档校验、复数路径 `textures/blocks/*.png` 双向受控解析、`mods/<mcversion>/*.jar` 扫描与 `mcmod.info` 解析、1.8–1.12.2 原版状态映射、内置流体回退与 `forge_marker: 1` 声明式解析，以及 1.6.1–1.7.10 原版静态几何贴图合成与无多面歧义简单 Mod 同名贴图近似/未知 Mod 降级诊断。默认离线回归 168 套共 780 项中 **672** 项通过、**108** 项按条件跳过、0 失败；显式启用本机 1.7.10 Forge 实例只读校验后 **674** 项通过、**106** 项按条件跳过、0 失败，纹理图集与可见面构建成功且存档/客户端文件零修改，`verifyCoreIsolation` 通过。[0.7.8 验证记录](V0.7.8.md#regression)

- **1.13–1.21.x 客户端准入、旧版群系与着色回退。** 0.7.7 覆盖 1.14–1.16.5 整数型 `pack_version` 与 1.13.x `pack.mcmeta` 客户端 JAR 准入、1.13–1.17.1 及 `byte[256]` `Level.Biomes` 群系解码、`pack_format 4–14` 权重抽取与 `multipart` 整组取反、旧矮草 `minecraft:grass`、未收录类指纹客户端的通用原版着色近似与 1.13–1.19.2 缺失内置数据注册表时的维度高度／群系气候回退，以及叠加资源源的基础客户端能力委托，同时保持 1.20.1 / 1.21.1 / 1.21.5 / 26.1 / 26.3 已登记指纹与篡改拒绝边界不变。默认离线回归 167 套共 777 项中 **670** 项通过、**107** 项按条件跳过、0 失败；显式启用本机 1.7.10 Forge 存档校验时 **671** 项通过、**106** 项按条件跳过、0 失败，`verifyCoreIsolation` 通过。[0.7.7 验证记录](V0.7.7.md#regression)

- **预扁平化存档兼容。** 0.7.6 覆盖 `Blocks`/`Add`/`Data` 预扁平化区块、1.7.x `FML.ItemData` 与 1.8–1.12.2 `FML.Registries` 数字 ID 还原、原版 `0..255` 方块与染色附加值归一、传统 `DIM<整数>` 维度发现、`level.dat` 二维瓦片／摘要／三维场景签名联动及大写 Mod ID 简化降级。默认回归 659 项通过、107 项按条件跳过、0 失败；显式启用本机 1.7.10 Forge 存档校验时 660 项通过、224 区块零失败、三维准星正常且读取前后文件零写入，`verifyCoreIsolation` 通过。[审查与实测](V0.7.6.md#review-and-regression)
- **二维与三维。** 0.7.5 `run-app.bat` 和便携版均实测可在程序内选定 Intel UHD 或 RTX 并重启生效。二维窗口确认设备后恢复 Windows 原显卡设置；三维由系统 OpenGL 选择设备，本机使用 RTX。中文输入法下可直接用 WASD 导航。[首屏与型号](V0.7.5.md#gpu-model) · [便携偏好与输入](V0.7.5.md#gpu-input)
- **地图工具。** 图标测距在连线上方实时显示距起点的 X、Z 格数，完成或取消后隐藏。9 项 JavaFX 交互回归通过，并用真实存档的缓存地图核对显示。
- **模型与着色。** 六个现代存档、14 个样区的模型和成图已对照。另核对五个原版客户端的 5,631 个代表状态；26.1 仅核验客户端资源。「休伯利安 新档」冰水遮挡已用 12 个视角复测。[模型覆盖](V0.7.5.md#model-check) · [冰水遮挡](V0.7.5.md#ice-check)
- **自制扩展。** 按随包提示词构建独立树叶着色插件，在「山隅闲庭」核对安装、重启恢复和着色效果。项目批处理与便携 EXE 均已用鼠标实测启停。[真实存档对照](V0.7.5.md#local-extension) · [启停修复](V0.7.5.md#plugin-apply)
- **性能。** 2560×1440 实测硬件二维交互为 68.6–72.1 FPS，CPU 二维为 40.3–47.9 FPS，原生三维单区块首帧为 2.42–2.70 秒。该轮早于冰水修复，覆盖本机的日常浏览与局部预览。[设备、内存与帧率](V0.7.5.md#settings-check)

## 剩余边界与下一步

- 0.8.1 已完成双模块拆分、无 UI 开发者 SDK、六维审查修复、全量离线自动化验证、独立 SDK 分发构件与 Windows x64 便携包构建；云端 JitPack 远程拉取留待 `v0.8.1` 标签公开后核验。
- 0.8.0 已完成六维代码审查、全量离线自动化回归与隐藏窗口 OpenGL 3.3 像素读回验证；尚未单独记录本机多群系大选区原生三维窗口人工目视验收。[后续范围](DEFERRED_WORK.md)
- 0.7.8 已通过本机 1.7.10 Forge 实例自动化只读三维纹理验证；0.7.7 与 0.7.8 尚未执行 1.8–1.19.4 真实客户端样本现场复核与原生窗口人工验收，`block-defaults.tsv` 历史数据版本补充留待后续评估。[后续范围](DEFERRED_WORK.md)
- 存档库、命名地点、连线、PNG 导出和浅深主题已有人工检查。窄窗口工具栏收纳与详情动效有本地布局检查，尚无该次联动调整的人工结论。
- 11 月 12 日固定 S/M/L 指标的未达项保留在版本记录。
- 爱发电赞助入口属于后续意向，未实现；游戏外生成未探索区块仅作可行性讨论。[后续范围](DEFERRED_WORK.md)
