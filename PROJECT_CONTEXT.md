# MC World Explorer 项目上下文

## 核心目标

MC World Explorer 用于在不启动 Minecraft 客户端的情况下，快速、独立、严格只读地浏览 Java 版世界，帮助玩家回忆内容、定位建筑并欣赏作品。

它是世界浏览工具，不是启动器、Mod、客户端替代品或世界编辑器。速度与资源占用必须以测量证明，不能把产品愿景当作已经达到的性能保证。

## 当前定位

- Java V0.6.0 是当前稳定版，保留在 `main` 和 Git 历史中。
- Java V0.3.1 是二维地图行为与体验基线。
- `tauri-rewrite` 是 V0.7 内部迁移分支，使用 Tauri、Rust、Vue 和 Three.js 重实现 V0.6 核心能力。
- V0.7 完成并通过对等验收前，不替代 Java 稳定版。

两条本地工作树属于同一个仓库。Java 稳定工作树只作为行为、实现和性能参考，不在 Tauri 迁移任务中修改。

## 已确认技术职责

- Tauri 2：Windows 桌面壳、窗口、Command、权限和应用状态。
- Rust 应用层：世界会话、任务调度、错误映射、协议编码和关闭协调。
- `mcwe-core`：不依赖 Tauri 或前端的 Minecraft 只读解析、表采样和网格算法。
- Vue 3 与 TypeScript：单窗口界面、前端状态和严格 IPC 解码。
- Canvas 2D：动态二维地图和二维交互覆盖层。
- Three.js / WebGL 2：主窗口内嵌的有限三维显示。

前端不直接读取 Minecraft 文件；Tauri Command 不承载 NBT、Region、地图采样或网格算法；`mcwe-core` 不依赖 WebView。

## 阶段状态与历史能力

当前已实现能力、未验收内容及下一门禁只在 [当前阶段状态](docs/progress/CURRENT.md) 更新。V0.7.1 和 V0.7.2 的完成事实、既有只读世界来源、便携 WebView 数据、世界选择与解析、有限二维/三维、混合 IPC 和三维范围预设的验收概况保留在 [历史 Progress](docs/progress/)；本页不另写一份随阶段变化的能力清单。

## 安全与便携边界

1. Minecraft 存档严格只读，不提供写入、修复、删除、移动或重命名 API。
2. 前端不获得任意路径、文件系统写入、Shell、sidecar 或网络能力。
3. 程序数据只能位于 EXE 便携目录的 `cache/`、`logs/`、`exports/` 和 `config/`。
4. 当前 Windows 核心拒绝重解析点和不兼容共享句柄；读取不保证运行中世界具备跨文件一致性。
5. 世界切换、任务取消和关闭必须拒绝旧会话或旧代次结果并释放文件、内存、WebView 和 GPU 资源。

最终 Windows x64 发行目标是无需安装、不附带 JVM、Node.js、sidecar 或并列 DLL 目录的单个 EXE，并依赖系统已有的 WebView2 Runtime。该目标不等于已经获得发布授权。

## 迁移原则

- Java 稳定行为默认继承；框架迁移不自动授权改变用户体验、默认值、回退顺序或错误语义。
- 缺失、不支持、损坏、取消、未知方块视觉回退和请求失败必须保持可区分。
- 每次只实施一个明确目标，不把二维、三维和其他版本重构混在同一任务。
- 资源上限、取消、失效和关闭顺序必须明确，不能通过无界并发、吞错或删除限制换取速度。
- 旧版疑似缺陷先建立 Issue，由开发者决定是否改变行为。

V0.7 不实现存档编辑、自由飞行、三维动态区块流送、实体、天气、动态光影、完整 Mod 模型或原版资源包渲染。

## 文档与门禁

重大依据见 [重大决策目录](docs/decisions/README.md)。Java 稳定行为继承以 DECISION-022 为准。

`DEVELOPMENT_RULES.md` 规定 Decisions、Specs、Issues、Progress 和 Superpowers plans 的职责。方案批准、实施完成、里程碑完成、提交、推送、构建、打包和发布是相互独立的授权。
