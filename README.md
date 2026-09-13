<p align="center">
  <img src="docs/assets/mc-world-explorer.png" alt="MC World Explorer 图标" width="144">
</p>

# MC World Explorer — Tauri 迁移分支

像查看照片一样查看 Minecraft 世界：独立、严格只读的 Minecraft Java 版世界浏览工具。

> 当前 `tauri-rewrite` 是内部重构分支，尚不能替代稳定 Java V0.6.0。V0.7.2 已完成目录发现、世界选择、有限二维表面和最大 8×8 区块简化三维的端到端垂直切片；二维地图速度与完整度问题簇以及真实负载关闭预算保留为活动问题，由 V0.7.3 重新规划和解决。

## 稳定版与迁移版

- 稳定 Java V0.6.0 保留在 main，具备存档信息、多维度二维地图和受控范围简化三维预览。使用稳定版请查看 [V0.6.0 发布页](https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.6.0)，完整解压并保留其 app/runtime 目录，不要只移动旧版 EXE。
- V0.7 使用 Tauri 2、Rust、Vue 3、TypeScript 和 Vite 迁移 V0.6；V0.7.2 已引入 Three.js / WebGL 2 的有限批次简化三维初版。
- V0.7.1–V0.7.6 为迁移里程碑，不创建正式标签或 Release，不自动合并 main。
- 原版纹理、世界考古等功能不在此次框架迁移中；迁移完成后再讨论后续路线。

## 当前可验证内容

- 单窗口页面显示世界摘要、`1024×1024` 二维表面、区块对齐选区和有限批次简化三维。
- 三维范围可选 1×1、3×3、5×5、8×8 区块，默认 3×3；点击地图定位，点击“生成三维”或按 Enter/空格确认。范围越大生成时间通常越长，支持取消。
- 类型化状态/控制 IPC 与 surface/mesh 二进制 IPC、安全中文错误、响应身份和畸形数据检查。
- Rust 独立只读解析核心、便携路径、单世界会话、协作取消和过期结果隔离。当前通用单 worker 不能承载目标二维调度，已确认由有界专用地图 worker 替代二维路径。
- Canvas/Three.js 生命周期测试、Rust 单元/集成/文档测试和 Tauri mock IPC 测试。

V0.7.1 已完成，概况见 [V0.7.1 进度](docs/progress/V0.7.1.md)。V0.7.2 已完成核心垂直链路、真实存档只读和基础资源释放验证；当前二维限制及后续活动问题详见 [V0.7.2 进度](docs/progress/V0.7.2.md)。

## 开发环境与运行

当前正式目标为 Windows x64。需准备 Node.js/npm、Rust MSVC 工具链、MSVC C++ 构建工具和 Windows SDK，以及系统 WebView2 Runtime。已使用的具体版本与测试日期见进度记录；安装或修改系统工具链需要单独确认。

在此迁移工作树根目录执行：

```powershell
npm ci
npm run tauri dev
```

首次恢复依赖需要网络。以上是供开发者执行的说明，不自动授权代理安装工具、启动程序或修改环境。

`npm run dev` 只启动 Vite 前端，普通浏览器没有 Tauri IPC，会显示连接失败；这不等于 Rust 后端损坏。开发态路径根随开发 EXE 位置确定，不是固定的源码根目录。

## 自动化命令

```powershell
npm test
npm run typecheck
npm run build
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
```

`npm run build` 执行类型检查和前端资源构建，不生成桌面发布包。已有依赖缓存时 Cargo 可附加 `--offline`。三项 Windows 符号链接测试需要相应权限，默认明确忽略；真实存档集成测试同样默认忽略且必须显式提供已获批路径。完整专项验收方式与历史证据见进度记录，不将忽略视为通过。

V0.7.1 已完成原生 no-bundle 构建、单 EXE 生命周期、最终审查和真实存档只读验收，并已同步至 `origin/tauri-rewrite`。这些结果不自动授权合并、标签或发布。

## 存档安全与便携目标

Minecraft 存档始终只读，不提供编辑、修复、删除、移动或重命名能力。点击“选择存档总目录”可选择 `.minecraft`、`versions`、实例目录、`saves` 或单个世界，列表展示实例、存档名称与路径，点击候选后读取其受限范围。仍保留单世界目录入口，同一时刻只有一个活动世界；取消选择保留旧结果。目录列表仅在本次运行有效，不自动扫描或持久化最近目录。程序启动时只在 EXE 便携目录受控准备 `config/webview/`，不启用缓存、日志或导出写入。

程序数据只允许位于 EXE 所在便携目录的 cache、logs、exports、config，不进入存档、AppData 或注册表。WebView 实际落盘已验证未改变既有 AppData；关闭唯一主窗口后主进程与 WebView2 子进程自然退出，便携目录锁正常释放。

最终目标为一个可直接运行的 `MC-World-Explorer.exe`，无需 JVM、Node.js、sidecar、并列 DLL 或外置资源目录，但依赖系统已有 WebView2 Runtime。当前没有完成该正式发行验收，也没有发布此迁移版 EXE。

## 项目文档

- [项目上下文](PROJECT_CONTEXT.md)
- [V0.7 迁移路线](PROJECT_ROADMAP.md)
- [当前项目结构](PROJECT_STRUCTURE.md)
- [V0.7.1 里程碑概况](docs/progress/V0.7.1.md)
- [V0.7.2 进度](docs/progress/V0.7.2.md)
- [里程碑 Progress 记录规范](docs/progress/README.md)
- [当前技术规格索引](docs/specs/README.md)
- [V0.7.2 二进制 IPC 协议](docs/specs/V0.7.2-binary-protocol.md)
- [重大决策分类与编号规则](docs/decisions/README.md)
- [迁移技术栈决策 DECISION-011](docs/decisions/tauri/DECISION-011.md)
- [Windows 只读边界 DECISION-012](docs/decisions/tauri/DECISION-012.md)
- [WebView 便携数据决策 DECISION-013](docs/decisions/tauri/DECISION-013.md)
- [WebView 关闭生命周期决策 DECISION-014](docs/decisions/tauri/DECISION-014.md)
- [世界目录选择决策 DECISION-015](docs/decisions/tauri/DECISION-015.md)
- [Rust NBT 基础决策 DECISION-016](docs/decisions/tauri/DECISION-016.md)
- [当前存档解析兼容决策 DECISION-017](docs/decisions/tauri/DECISION-017.md)
- [Rust 核心三维网格决策 DECISION-018](docs/decisions/tauri/DECISION-018.md)
- [混合 IPC 决策 DECISION-019](docs/decisions/tauri/DECISION-019.md)
- [单个只读 WorldSession 决策 DECISION-020](docs/decisions/tauri/DECISION-020.md)
- [Canvas 2D 动态瓦片视口决策 DECISION-021](docs/decisions/tauri/DECISION-021.md)
- [Java 稳定行为继承决策 DECISION-022](docs/decisions/tauri/DECISION-022.md)
- [二维地图有界渐进调度决策 DECISION-023](docs/decisions/tauri/DECISION-023.md)
- [问题记录规范](docs/issues/README.md)与[编号索引](docs/issues/ISSUE_INDEX.md)
- [Java V0.6 历史验收](docs/progress/V0.6.md)

旧 FAQ、颜色规则和 V0.1–V0.6 里程碑概况作为 Java 基线参考保留，不代表本分支已具备相同能力。本地 `DEVELOPMENT_RULES.md` 与 `docs/superpowers/`、`scripts/`、`packaging/` 受 Git 忽略保护，不得擅自纳入提交。

每次只执行获批目标；里程碑完成、提交和推送分别确认，不自动进入下一里程碑。

## 许可证

[GNU General Public License v3.0](LICENSE)。
