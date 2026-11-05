# 文档目录

从使用问题进入对应说明，或展开开发、版本和决策分类。当前 Java 主线定版为 **0.7.4**，已完成正式发布；[正式版获取入口](https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.7.4)。具体结果、已知边界和未测项以[当前进度](progress/CURRENT.md)为准。

<details>
<summary><strong>使用与排查</strong></summary>

| 文档 | 用途 |
|---|---|
| [项目首页](../README.md) | 下载、快速开始与基本操作 |
| [常见问题](FAQ.md) | 版本安排和使用行为；历史说明以文中适用版本为准 |
| [资源解析范围](MODEL_PROTOCOL_CAPABILITIES.md) | 当前本体支持什么、哪些内容可能降级 |
| [未解析内容诊断](MODEL_GAP_TRIAGE.md) | 从方块 ID、原因和资源来源定位问题 |
| [方块颜色规则](方块颜色规则.md) | 颜色与简化显示规则，区分实际纹理染色和替代外观 |

</details>

<details>
<summary><strong>开发与扩展</strong></summary>

| 文档 | 用途 |
|---|---|
| [插件开发说明](PLUGIN_DEVELOPMENT.md) | 当前 core.1 接口、独立编译示例、安装启停及清理边界 |
| [界面设计规范](UI_DESIGN_GUIDE.md) | 主界面与资源、诊断、插件分区约定 |
| [构建与发布准备](RELEASING.md) | 可复用的 Windows 打包方式与发布前核对 |
| [项目上下文](../PROJECT_CONTEXT.md) | 接手时核对项目架构、状态与约束 |
| [项目路线](../PROJECT_ROADMAP.md) | 阶段目标与后续方向；当前执行以版本计划为准 |
| [项目结构](../PROJECT_STRUCTURE.md) | 正式源码、资源、测试与工具的目录说明 |

从源码运行需要 JDK 21。在项目根目录执行：

```powershell
.\gradlew.bat run
```

Windows 下也可双击根目录的 `run-app.bat`。首次构建需要下载 Gradle 和依赖。当前正式构建输出位于 `build/core-0.7.4/`；本体不会构建或预装专用 Mod 插件，也不提供旧探索版本的 SDK 分发包。插件编译与接口限制请按上方开发说明操作。

</details>

<details>
<summary><strong>版本进度 · 由新到旧</strong></summary>

[当前进度](progress/CURRENT.md)记录接续位置；[版本进度目录](progress/README.md)汇总各阶段。[0.7.4 版本说明](releases/V0.7.4.md)记录本期交付内容与限制。开发版本不一定对应已发布软件包。

| 版本 | 记录 |
|---|---|
| [V0.7.4](progress/V0.7.4.md) | 纯本体通用解析、三维交互与本期收尾 |
| [V0.7.3](progress/V0.7.3.md) | 实例资源接入与通用模型解析 |
| [V0.7.2](progress/V0.7.2.md) | 资源驱动的完整与非完整方块 |
| [V0.7.1](progress/V0.7.1.md) | 三维视觉资源基础 |
| [V0.7](progress/V0.7.md) | 地图浏览工作区与界面层级 |
| [V0.6](progress/V0.6.md) | 小范围三维预览，发布版本为 V0.6.0 |
| [V0.5](progress/V0.5.md) | 三维数据与渲染基础，内部开发阶段 |
| [V0.4](progress/V0.4.md) | 三维技术验证，内部开发阶段 |
| [V0.3.1](progress/V0.3.1.md) | 发布前完善 |
| [V0.3](progress/V0.3.md) | 交互地图浏览器 |
| [V0.2.1](progress/V0.2.1.md) | 多维度预览与洞穴分层 |
| [V0.2](progress/V0.2.md) | 世界缩略图 |
| [V0.1.1](progress/V0.1.1.md) | 质量与存档兼容性加固 |
| [V0.1](progress/V0.1.md) | 存档管理器 |

[后续版本范围](progress/DEFERRED_WORK.md)用于后续立项，不是本期执行任务，也不表示相关实现随当前源码交付。

</details>

<details>
<summary><strong>设计决策</strong></summary>

[决策目录](decisions/README.md)按编号从新到旧整理。当前范围首先核对 [DECISION-024：纯本体交付边界](decisions/DECISION-024.md)，受限数据读取还需结合 DECISION-019、020、022。旧决策说明当时取舍，不能越过当前计划恢复已退出交付的探索路线。

</details>

<details>
<summary><strong>审查与维护</strong></summary>

- [审查记录规范](issues/README.md)：问题格式、状态与归档规则。
- [审查编号索引](issues/ISSUE_INDEX.md)：按文件查编号与记录。
- [已解决记录目录](issues/resolved/)：按版本查看历史审查；不在此逐一展开文件清单。

自动测试、真实存档、原生界面和性能实测分别记录。条件跳过不计通过，历史测试数量不能代替当前功能覆盖。

</details>
