# MC World Explorer 项目上下文

## 核心目标

像查看照片一样查看 Minecraft 世界：不启动 Minecraft 客户端，快速、独立、严格只读地浏览 Java 版存档，帮助玩家回忆内容、定位建筑并欣赏作品。

这是世界浏览工具，不是启动器、Mod、客户端替代品或世界编辑器。“将游戏存档变成可随时回顾的数字资产”是产品愿景；速度与资源占用需要实测，不把愿景当作已达到的性能保证。

## 当前定位与稳定基线

- 稳定旧版为 Java V0.6.0，保留在 `main` 和 Git 历史中；发布与验收历史见 [V0.6 记录](docs/progress/V0.6.md)。
- V0.3.1 是长期保留的二维地图行为基线。
- 当前分支 `tauri-rewrite` 是内部迁移工程，不是面向普通用户的稳定发布版。
- V0.7 全线用于迁移 V0.6 的核心功能，不再承接旧路线中的“原版视觉资源”开发。
- V0.7.1“工程与边界”已完成并经开发者确认；V0.7.2 及后续里程碑均未开始，范围见 [路线图](PROJECT_ROADMAP.md)。

两条本地工作树分别为 `D:\github_project\MC World Explorer`（main）和 `D:\github_project\MC-World-Explorer-Tauri`（迁移）。它们属于同一个仓库，不重建仓库，不丢弃旧历史；不得在迁移任务中修改稳定工作树。

## 已确认技术与职责

- Tauri 2：Windows 桌面壳、Command、状态与权限边界。
- Vue 3、TypeScript、Vite、npm：界面及类型化 IPC 服务。
- Rust MSVC 与 Cargo workspace：应用协调和独立 `mcwe-core`。
- Three.js / WebGL 2：已确认的后续内嵌三维方向，V0.7.1 不引入。
- 正式开发、测试和最终发行目标为 Windows x64，其他平台未承诺支持。

`src/` 不直接访问 Minecraft 文件；`src-tauri/` 不承载 NBT、Region、地图或网格算法；`crates/mcwe-core/` 不依赖 Tauri 或前端。模块现状见 [项目结构](PROJECT_STRUCTURE.md)。

## 当前实际能力

截至 2026-09-06，Task 5–14 及其后的真实存档只读验收已完成工程骨架、基础实现、文档同步、构建验证与真实边界验证：

- 最小 Vue 页面，通过唯一的 `app_status` Command 查询版本、基础后端状态和便携路径。
- 固定中文错误、响应字段检查及组件卸载后的迟到结果隔离。
- Rust 应用层单槽位任务标识、协作取消与过期结果拒绝基础，尚未接入业务任务 UI。
- `WorldSource` / `WorldFile` 只读文件抽象与 Windows 路径保护；核心尚不解析 Minecraft 格式。
- `PortablePaths` 保持纯路径计算；Tauri 启动层会在 WebView 创建前受控准备 `EXE/config/webview/`，拒绝世界重叠、重解析点及环境/策略覆盖，不启用其他缓存、日志或导出写入。

当前不能扫描或展示世界、解析 NBT/MCA、浏览地图或生成三维场景。上述能力属于稳定 Java 版及待迁移范围，不能归为当前 Tauri 实现。

真实 Tauri 页面、IPC、WebView 便携落盘、人工关闭后的自然退出、Task 14 最终审查及真实存档只读均已有带日期证据；开发者已确认 V0.7.1 完成。详细证据及后续门禁以 [V0.7.1 进度](docs/progress/V0.7.1.md) 为准；完成不自动授权提交、推送或开始 V0.7.2。

## 安全和便携边界

1. Minecraft 存档严格只读，不提供写入、修复、删除、移动或重命名 API。
2. 程序数据只允许位于 EXE 所在便携目录的 `cache/`、`logs/`、`exports/`、`config/`，不得写入世界、AppData 或注册表。
3. WebView 便携初始化按 [DECISION-015](docs/decisions/DECISION-015.md) 实现并通过实际落盘验证；主窗口协调关闭按 [DECISION-016](docs/decisions/DECISION-016.md) 实现，并通过实际自然退出与目录解锁验证。
4. 当前 Windows 核心拒绝所有重解析点，冲突句柄返回错误；可能限制链接式存档或云同步路径。读取不保证正在运行世界的跨文件一致性，详见 [DECISION-014](docs/decisions/DECISION-014.md)。
5. 不引入 sidecar、Shell 或当前任务不需要的网络、文件系统写插件。

最终发行目标是单个可直接运行的 `MC-World-Explorer.exe`，无需附带 JVM、Node.js、并列 DLL 或外置资源目录，但依赖系统已有 WebView2 Runtime。此目标尚未完成最终验收，不等于当前已提供此发布物。

## 迁移原则与排除项

复用 V0.6 已验证算法的语义和测试证据，不逐类逐行翻译。先分析旧输入、输出及失败边界，再重实现、对照验证，最后接入界面。旧版疑似缺陷先记录，由开发者决定是否改变行为。

V0.7 不实现原版纹理/模型/图集、自由移动、动态区块流送、实体、天气、动态光影、完整 Mod 渲染、离线模拟或存档编辑；V0.7.1 也不提前迁移 NBT、Anvil、地图及网格算法。

世界考古、成长记录、世界差异等只保留为迁移完成后再讨论的候选方向，不分配版本或提前实现。V0.8 及以后保持未规划。

## 规则、历史与门禁

重大依据为 [DECISION-013](docs/decisions/DECISION-013.md)、[DECISION-014](docs/decisions/DECISION-014.md)、[DECISION-015](docs/decisions/DECISION-015.md) 和 [DECISION-016](docs/decisions/DECISION-016.md)。旧决策的继承和替代关系以 DECISION-013 为准；历史进度与验收快照不反写。

本地 `DEVELOPMENT_RULES.md` 继续生效且被 Git 忽略。每次只执行获批目标；方案、实施、完成、提交、推送、构建验证与发布等授权不得互相替代。V0.7 里程碑期间不创建阶段性版本提交，不创建标签或 Release，不合并 main，不自动进入下一里程碑。

