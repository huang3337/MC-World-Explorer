# MC World Explorer 项目结构

本页描述 tauri-rewrite 的实际工程，不是 Java V0.6 结构或未来目录草案。V0.7.1 已完成并经开发者确认，V0.7.2 尚未开始。

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
│  ├─ App.vue / App.test.ts             最小状态页面与组件测试
│  ├─ styles.css                       验证页面样式
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
│     │  ├─ mod.rs                    生产与 mock 共用注册
│     │  └─ app_status.rs             状态查询、DTO 及 mock IPC 测试
│     ├─ storage/
│     │  ├─ mod.rs
│     │  ├─ portable_paths.rs         EXE 便携路径派生及测试
│     │  ├─ webview_data.rs           WebView 便携目录准备、保护及覆盖检测
│     │  └─ webview_lifecycle.rs      保护句柄的幂等、并发安全释放
│     └─ tasks/
│        ├─ mod.rs
│        └─ registry.rs               单槽位标识、取消与接纳测试
├─ crates/mcwe-core/
│  ├─ Cargo.toml                      无第三方依赖
│  ├─ src/
│  │  ├─ lib.rs                       核心导出，禁止 unsafe
│  │  ├─ error.rs                     稳定核心错误
│  │  └─ world_source.rs              Windows 只读边界及测试
│  └─ tests/
│     └─ real_world_readonly.rs       默认忽略的真实存档只读集成测试
└─ docs/
   ├─ assets/                         项目图标等既有文档资源
   ├─ progress/                       历史快照及 V0.7.1 当前记录
   ├─ issues/                         活动问题、README、ISSUE_INDEX
   │  └─ resolved/                    按版本归档
   └─ decisions/                      重大决策及继承关系
```

旧 FAQ、方块颜色规则等辅助材料仍保留，适用 Java 历史基线，不能作为 Tauri 已实现功能说明；本次没有改写这些历史材料。三维 renderer、世界解析、地图、stores 等尚未实现，不为未来能力预建空目录。

## 调用与职责边界

当前启动链路先派生 PortablePaths、拒绝 WebView2 外部覆盖并准备 `EXE/config/webview/`，再从配置显式创建唯一 main 窗口。关闭 main 时阻止默认销毁，先幂等释放目录保护句柄，再请求 Tauri 正常退出；其他退出来源在 ExitRequested 再执行安全空操作。页面链路为 App.vue → services/backend.ts → app_status Command → 启动时管理的同一 PortablePaths 结果；页面展示版本、状态和路径，不读取世界。

- 前端只消费 DTO，不调用文件系统；普通浏览器没有 Tauri 时显示 IPC 错误。
- 应用层协调权限、错误和任务，不承担领域解析算法。当前唯一 Command 为 app_status，明确检查 main 窗口标签。
- mcwe-core 独立于框架，WorldFile 只暴露 Read/Seek，不暴露原始 File 或写接口。世界读取边界见 [DECISION-014](docs/decisions/DECISION-014.md)，WebView 数据准备和关闭生命周期见 [DECISION-015](docs/decisions/DECISION-015.md) 与 [DECISION-016](docs/decisions/DECISION-016.md)。
- TaskRegistry 还没有业务执行器或界面任务接线。PortablePaths 不是写入授权或世界快照。
- Tauri 当前 capability 仅 core:default，无 Shell、文件系统写插件或 sidecar；配置中的 CSP 目前为 null，不应描述为已完成最终安全加固。

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

[DECISION-013](docs/decisions/DECISION-013.md) 定义技术迁移及旧决策继承，[DECISION-014](docs/decisions/DECISION-014.md)、[DECISION-015](docs/decisions/DECISION-015.md) 与 [DECISION-016](docs/decisions/DECISION-016.md) 分别约束世界只读来源、WebView 便携数据和主窗口协调关闭；[进度记录](docs/progress/V0.7.1.md) 保存已实现内容、测试快照和后续门禁。
