# MC World Explorer 项目结构

本页只描述 `tauri-rewrite` 的实际工程结构和模块职责，不承担阶段进度记录。已验证能力、未验收内容和下一门禁只见[当前阶段状态](docs/progress/CURRENT.md)；Java V0.6 结构和未来目录草案不在本页。

## 正式工程

```text
MC-World-Explorer-Tauri/
├─ PROJECT_CONTEXT.md
├─ PROJECT_ROADMAP.md
├─ PROJECT_STRUCTURE.md
├─ README.md
├─ LICENSE
├─ .gitignore
├─ package.json / package-lock.json     npm 命令及锁定依赖
├─ index.html                          Web 页面入口
├─ vite.config.ts                      Vue 开发服务器及构建
├─ vitest.config.ts                    jsdom 单次测试配置
├─ tsconfig*.json                      应用和工具类型检查
├─ Cargo.toml / Cargo.lock             Cargo workspace 及锁定依赖
├─ src/
│  ├─ main.ts                          Vue 挂载与样式入口
│  ├─ env.d.ts                         前端类型声明
│  ├─ App.vue / App.test.ts             单窗口业务布局与页面测试
│  ├─ styles.css                       业务视图、状态与保留态样式
│  ├─ components/                      Canvas 2D 与 Three.js 有限视图
│  ├─ composables/                     页面状态机、Canvas 和 Three.js 生命周期
│  ├─ protocol/                        surface/mesh/map-tile/map-batch v1 解码与协议测试
│  ├─ services/
│  │  ├─ types.ts                      IPC DTO 和前端错误类型
│  │  ├─ backend.ts                    唯一生产 invoke 入口及响应检查
│  │  └─ backend.test.ts               服务契约测试
│  └─ test/setup.ts                    IPC mock 与测试清理
├─ src-tauri/
│  ├─ Cargo.toml
│  ├─ build.rs                         Tauri 资源构建及 Windows 测试清单支持
│  ├─ tauri.conf.json                  主窗口、开发地址及资源配置
│  ├─ capabilities/main.json           main 窗口 core:default
│  ├─ icons/                          四个 Windows 应用图标资源
│  └─ src/
│     ├─ main.rs / lib.rs              应用入口、状态、Command 注册与协调退出
│     ├─ error.rs                     核心/任务错误到安全 AppError
│     ├─ commands/
│     │  ├─ mod.rs / tests.rs         生产与 mock 共用注册、严格 IPC 测试
│     │  ├─ app_status.rs             状态查询及 DTO
│     │  ├─ select_world.rs           官方目录选择、后台候选校验与只读会话替换
│     │  ├─ world_library.rs          目录扫描、候选编号与列表打开命令
│     │  ├─ load_surface.rs           精确 1024×1024 方块表面任务
│     │  ├─ load_map_tile.rs          独立地图瓦片兼容命令
│     │  ├─ map_viewport.rs           视口目标与增量批次命令
│     │  ├─ build_mesh.rs             最大 8×8 网格任务与累计解码预算
│     │  └─ cancel_task.rs            幂等取消当前任务
│     ├─ protocol/                    surface/mesh/map-tile/map-batch v1 二进制编码
│     ├─ storage/
│     │  ├─ mod.rs
│     │  ├─ portable_paths.rs         EXE 便携路径派生及测试
│     │  ├─ webview_data.rs           WebView 便携目录准备、保护及覆盖检测
│     │  └─ webview_lifecycle.rs      保护句柄的幂等、并发安全释放
│     ├─ tasks/
│     │  ├─ mod.rs / registry.rs      通用任务标识、取消与接纳
│     │  ├─ executor.rs               非地图重任务的单 worker 执行器
│     │  └─ map_scheduler.rs / map_poll.rs / map_results.rs
│     │                                二维双 worker、有界调度与增量结果
│     └─ world_session.rs             单个只读世界会话及代次
├─ crates/mcwe-core/
│  ├─ Cargo.toml                      fastnbt、flate2 与 serde 边界
│  ├─ src/
│  │  ├─ lib.rs / error.rs / limits.rs / cancel.rs
│  │  ├─ world_source.rs              Windows 只读边界及测试
│  │  ├─ world/ / nbt/ / anvil/      世界目录布局扫描、元数据、受限 NBT 与 Region 读取
│  │  ├─ chunk/ / area/ / surface/   Palette 解码、方块边界与表面采样
│  │  ├─ map/                        动态地图瓦片生成与会话内缓存
│  │  └─ mesh/                        分类、邻接与确定性贪心合并
│  └─ tests/
│     ├─ level_dat.rs / anvil_region.rs / chunk_decode.rs
│     ├─ surface_slice.rs / mesh_slice.rs / map_cache.rs
│     └─ real_world_readonly.rs       默认忽略的真实存档只读集成测试
└─ docs/
   ├─ assets/                         项目图标等既有文档资源
   ├─ progress/                       唯一实时状态与历史里程碑快照
   ├─ specs/                          版本化协议与实现规格
   ├─ issues/                         活动问题、README、ISSUE_INDEX
   │  └─ resolved/                    按版本归档
   └─ decisions/                      全局连续编号的重大决策
      ├─ README.md                    分类与编号规则
      ├─ java/                       Java V0.1–V0.6 历史决策
      └─ tauri/                      V0.7 Tauri 迁移决策
```

旧 FAQ 等辅助材料仍保留，适用 Java 历史基线，不能单独作为 Tauri 已实现功能说明。目录中的源码存在不等于真实存档验收通过；完成程度只在当前阶段状态中概括。

## 调用与职责边界

启动链路先派生 PortablePaths、拒绝 WebView2 外部覆盖并准备 `EXE/config/webview/`，再从配置显式创建唯一 main 窗口。关闭 main 时先协调后台任务和会话释放，再幂等释放目录保护句柄并请求 Tauri 正常退出。业务链路为 App.vue → services/backend.ts → 专用 Commands → WorldSession/有界任务域 → mcwe-core；控制信息使用 JSON，表面、地图瓦片、视口批次和网格使用受限 raw ArrayBuffer。

- 前端只消费 DTO，不调用文件系统；普通浏览器没有 Tauri 时显示 IPC 错误。
- 应用层协调权限、错误、会话、任务和协议编码，不承担 NBT、Chunk、表面或网格算法；所有业务 Command 均检查 main 窗口与本地来源并拒绝额外参数。
- mcwe-core 独立于 Tauri，WorldFile 只暴露 Read/Seek，不暴露原始 File 或写接口；解析分配、坐标和网格规模均有固定上限。世界读取边界见 [DECISION-012](docs/decisions/tauri/DECISION-012.md)，WebView 数据准备和关闭生命周期见 [DECISION-013](docs/decisions/tauri/DECISION-013.md) 与 [DECISION-014](docs/decisions/tauri/DECISION-014.md)。
- TaskRegistry 与单 worker TaskExecutor 用于非地图重任务；二维视口使用独立双 worker 有界调度、结果队列和增量轮询。新请求按任务域替换或重新绑定工作，并由任务、会话和页面代次拒绝迟到结果。PortablePaths 仍不是任意写入授权。
- Tauri 当前 capability 仅 core:default，无 Shell、文件系统写插件或 sidecar；生产 CSP 限制本地资源与 IPC 连接，devCsp 为 null，不能据此宣称最终安全验收完成。

Rust 单元测试位于相应源文件的 cfg(test) 模块，核心另有编译失败文档测试和必须显式提供真实路径的默认忽略集成测试；Vue 与服务测试与源码相邻。没有继续沿用 Java 的 src/test/java 布局。

## 受忽略保护的本地内容

- `DEVELOPMENT_RULES.md`：本地 V0.7 规则。
- `docs/superpowers/`：设计与实施过程文档。
- `scripts/`、`packaging/`：本地验证和打包工具，不是用户发布物。
- `node_modules/`、`dist/`、`target/`、`.vite/`、`.vitest/`、`coverage/`、`src-tauri/gen/schemas/`：依赖或生成物。
- `MC/`：本地测试数据；`cache/`、`logs/`、`exports/`、`config/`：程序数据目录名称。

忽略不代表文件不存在，也不允许取消忽略或纳入提交。程序数据根由 EXE 所在位置确定，不固定等于源码工作树；当前启动只受控创建 WebView 所需的 `config/webview/`，其余便携路径仍仅查询。

## 稳定旧版与历史

相邻 main 工作树保留 Java 21 / Gradle / JavaFX / LWJGL V0.6，旧源码也保存在 Git 历史中。迁移工作树已移除旧 Java/Gradle 工程入口，不长期并存两个应用骨架。

[重大决策目录](docs/decisions/README.md) 规定 Java 与 Tauri 分类及全局连续编号；实际完成状态以 [CURRENT](docs/progress/CURRENT.md) 为准。
