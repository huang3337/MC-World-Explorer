# MC World Explorer 重大决策目录

本目录只保存当前仍有效、已经项目开发者确认的重大决策。决策用于说明长期技术或产品边界，不记录逐任务实施过程、测试流水、验收日志或已经被后续确认废止的旧方案。

## 目录

- `java/`：Java 稳定版决策，DECISION-001 至 DECISION-010。
- `tauri/`：`tauri-rewrite` 当前决策，DECISION-011 至 DECISION-023。

分类不改变全项目连续编号。

## 当前 Java 决策

| 编号 | 决策 |
|---|---|
| DECISION-001 | 采用严格便携的本地数据存储模式 |
| DECISION-002 | Region 采用标准 Anvil 与可扩展分层设计 |
| DECISION-003 | 多维度预览采用统一图层系统 |
| DECISION-004 | 二维浏览采用多级地图瓦片架构 |
| DECISION-005 | 三维渲染采用 LWJGL/OpenGL |
| DECISION-006 | 二维与三维共享方块状态解析核心 |
| DECISION-007 | 正式三维预览使用独立 LWJGL 窗口 |
| DECISION-008 | 三维窗口采用环绕、有限平移和中心缩放交互 |
| DECISION-009 | 内部 AI 计划文档不进入公开 Git 历史 |
| DECISION-010 | 内部验证与打包工具不进入公开 Git 历史 |

## 当前 Tauri 决策

| 编号 | 决策 |
|---|---|
| DECISION-011 | V0.7 采用 Tauri、Rust、Vue 和 Three.js 重构 V0.6 |
| DECISION-012 | Windows 只读来源使用逐级目录句柄保护 |
| DECISION-013 | WebView 数据固定使用 EXE 便携目录 |
| DECISION-014 | 关闭主窗口时协调释放资源并退出应用 |
| DECISION-015 | 世界发现与打开由受控 Rust Command 管理 |
| DECISION-016 | Rust NBT 基础采用 fastnbt 与 flate2 |
| DECISION-017 | 当前存档解析采用有限双布局兼容 |
| DECISION-018 | 由 Rust 核心生成简化三维渲染网格 |
| DECISION-019 | IPC 混合使用 JSON 控制信息与原始二进制密集数据 |
| DECISION-020 | Rust 管理单个只读 WorldSession |
| DECISION-021 | 二维地图使用 Canvas 2D 动态瓦片视口 |
| DECISION-022 | Tauri 重构默认继承 Java 稳定行为 |
| DECISION-023 | 二维地图采用独立有界渐进调度架构 |

## 编号与更新规则

1. Java 与 Tauri 决策使用同一个连续编号序列。
2. 新重大决策先扫描两个目录，以当前最大编号的下一号创建。
3. 当前下一编号为 DECISION-024。
4. 决策必须保留其原确认日期。
5. 仍有重大决策核心的文件直接更新为当前有效结论，并删去失效方案和实施过程。
6. 决策核心完全失效或文件本身不属于重大决策时，删除该文件；后续文件按确认日期和既有顺序连续重编号，不留空号。
7. 重编号必须同步标题、索引和所有非 Issue 引用。
8. Issue 的原始审查快照不得反写；必要的编号映射只能补充在快照之外。

## 内容边界

新决策至少包含状态、原确认日期、确认人、适用范围、简洁背景、当前最终选择、关键原因和长期影响。

以下内容通常不进入决策记录：

- Task 编号、文件修改顺序和逐步实施方案；
- 队列容量、缓存数值、重试次数和状态枚举等可调整参数；
- 协议逐字节布局、测试用例矩阵、命令输出和人工验收日志；
- 版本排期、完成状态、提交、推送、打包或发布记录；
- 已经被后续确认废止的旧范围或旧架构。

这些内容分别由当前 Specs、Issues、Progress 或 `docs/superpowers/` 承担。详细职责以 `DEVELOPMENT_RULES.md` 为准。
