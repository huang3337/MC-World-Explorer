<p align="center">
  <img src="docs/assets/mc-world-explorer.png" alt="MC World Explorer 图标" width="144">
</p>

# MC World Explorer — Tauri 迁移分支

像查看照片一样查看 Minecraft 世界：独立、严格只读的 Minecraft Java 版世界浏览工具。

> 当前 `tauri-rewrite` 是内部重构分支。V0.7.1“工程与边界”已完成，但仍不能替代稳定 Java V0.6.0；此分支目前只有工程与状态验证页面，还不能扫描世界、浏览地图或显示三维存档。

## 稳定版与迁移版

- 稳定 Java V0.6.0 保留在 main，具备存档信息、多维度二维地图和受控范围简化三维预览。使用稳定版请查看 [V0.6.0 发布页](https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.6.0)，完整解压并保留其 app/runtime 目录，不要只移动旧版 EXE。
- V0.7 使用 Tauri 2、Rust、Vue 3、TypeScript 和 Vite 迁移 V0.6；后续三维采用 Three.js / WebGL 2，当前尚未引入。
- V0.7.1–V0.7.6 为迁移里程碑，不创建正式标签或 Release，不自动合并 main。
- 原版纹理、世界考古等功能不在此次框架迁移中；迁移完成后再讨论后续路线。

## 当前可验证内容

- 最小页面显示应用版本、后端连接状态和 EXE 便携目录。
- 唯一类型化 app_status 调用、安全中文错误及异常响应检查。
- Rust 独立只读文件核心、便携路径计算、任务标识与取消基础。
- 前端测试、Rust 单元/文档测试和 Tauri mock IPC 测试。

真实 Tauri 页面、IPC、WebView 便携落盘、关闭最后窗口后的主进程自然退出和目录解锁均已验证；Task 14 最终审查、问题复审和真实存档只读验收已经通过，开发者已确认 V0.7.1 完成。V0.7.2 尚未开始，详细状态见 [V0.7.1 进度](docs/progress/V0.7.1.md)。

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

原生 no-bundle 构建、单 EXE 生命周期验证、Task 14 最终审查复审、真实存档只读验收和 V0.7.1 完成确认均已完成。本次文档同步不重新构建或启动 EXE，也不自动授权提交、推送或开始 V0.7.2。

## 存档安全与便携目标

Minecraft 存档始终只读，不提供编辑、修复、删除、移动或重命名能力。当前页面不读取世界；程序启动时只在 EXE 便携目录受控准备 `config/webview/`，不启用缓存、日志或导出写入。

程序数据只允许位于 EXE 所在便携目录的 cache、logs、exports、config，不进入存档、AppData 或注册表。WebView 实际落盘已验证未改变既有 AppData；关闭唯一主窗口后主进程与 WebView2 子进程自然退出，便携目录锁正常释放。

最终目标为一个可直接运行的 `MC-World-Explorer.exe`，无需 JVM、Node.js、sidecar、并列 DLL 或外置资源目录，但依赖系统已有 WebView2 Runtime。当前没有完成该正式发行验收，也没有发布此迁移版 EXE。

## 项目文档

- [项目上下文](PROJECT_CONTEXT.md)
- [V0.7 迁移路线](PROJECT_ROADMAP.md)
- [当前项目结构](PROJECT_STRUCTURE.md)
- [V0.7.1 进度与证据](docs/progress/V0.7.1.md)
- [迁移决策 DECISION-013](docs/decisions/DECISION-013.md)
- [Windows 只读边界 DECISION-014](docs/decisions/DECISION-014.md)
- [WebView 便携数据决策 DECISION-015](docs/decisions/DECISION-015.md)
- [WebView 关闭生命周期决策 DECISION-016](docs/decisions/DECISION-016.md)
- [问题记录规范](docs/issues/README.md)与[编号索引](docs/issues/ISSUE_INDEX.md)
- [Java V0.6 历史验收](docs/progress/V0.6.md)

旧 FAQ、颜色规则和 V0.1–V0.6 进度仍作为 Java 历史参考保留，不代表本分支已具备相同能力。本地 DEVELOPMENT_RULES.md 与 docs/superpowers、scripts、packaging 受 Git 忽略保护，不得擅自纳入提交。

每次只执行获批目标；里程碑完成、提交和推送分别确认，不自动进入下一里程碑。

## 许可证

[GNU General Public License v3.0](LICENSE)。
