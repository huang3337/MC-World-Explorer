<p align="center">
  <img src="docs/assets/mc-world-explorer.png" alt="MC World Explorer 图标" width="144">
</p>

<h1 align="center">MC World Explorer</h1>

<p align="center">
  只读的 Minecraft Java 版存档与交互地图浏览工具
</p>

<p align="center">
  <a href="https://github.com/huang3337/MC-World-Explorer/releases/latest"><img src="https://img.shields.io/github/v/release/huang3337/MC-World-Explorer?label=Release&color=2f81f7" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Windows-x64-0078D4?logo=windows" alt="Windows x64">
  <img src="https://img.shields.io/badge/Java-21-E76F00?logo=openjdk&logoColor=white" alt="Java 21">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-2da44e" alt="GPL-3.0 许可证"></a>
</p>

<p align="center">
  <strong><a href="https://github.com/huang3337/MC-World-Explorer/releases/latest">下载最新软件包</a></strong>
  ·
  <strong><a href="PROJECT_ROADMAP.md">查看项目路线图</a></strong>
  ·
  <strong><a href="docs/FAQ.md">常见问题</a></strong>
  ·
  <strong><a href="#从源码运行">从源码运行</a></strong>
</p>

## 当前软件包

| 最新软件包 | 支持平台 | 运行环境 |
|---|---|---|
| **V0.6.0** | Windows x64 | 内置 Java 21 |

当前便携版无需另外安装 Java，完整解压后即可运行。

[下载最新软件包](https://github.com/huang3337/MC-World-Explorer/releases/latest)

## 源码开发状态

| 最新完成源码 | 状态 | 当前开发阶段 | 状态 |
|---|---|---|---|
| **V0.6** | 小范围三维预览已完成 | **V0.7** | 尚未开始 |

> [!NOTE]
> V0.4 和 V0.5 是三维技术验证与内部基础阶段，没有对应的用户软件包。详细原因见[常见问题](docs/FAQ.md#为什么没有-v04-和-v05-软件包)。

## 关于项目

MC World Explorer 可以在不启动 Minecraft 的情况下扫描本地 Java 版世界、查看存档信息，并通过交互地图浏览已有区块、不同维度、玩家和传送门位置。

项目坚持严格只读访问 Minecraft 存档，缓存、日志、配置和导出文件均与原存档分离。

## 软件包发布路线

| 版本 | 发布状态 | 主要内容 |
|---|---|---|
| **V0.1.1** | 已发布 | 存档扫描、基础信息展示与只读能力加固 |
| **V0.2.1** | 已发布 | 多维度预览、地表总览、洞穴高度带与便携运行环境 |
| **V0.3.1** | 已发布 | 整合 V0.3 交互地图能力并完成最后完善 |
| **V0.6.0** | 当前最新软件包 | 首次加入受控范围选择和独立窗口三维预览 |

> [!NOTE]
> V0.3 是仅同步源码的开发版本，因此不列入已发布软件包版本。

## 核心能力

| 存档浏览 | 交互地图 | 定位与标记 |
|---|---|---|
| 扫描原版及版本隔离存档<br>支持游戏目录、实例和单个世界<br>显示世界信息、图标和基础坐标<br>后台加载并反馈空目录或错误 | 浏览主世界、下界、末地和可识别的 Mod 维度<br>地表总览与 32 格洞穴高度带<br>地图自由拖动与七级缩放<br>地图块缓存、缓存清理和视口 PNG 导出 | 输入 X/Z 坐标快速跳转<br>显示玩家和世界出生点<br>在已加载地图区块中标记下界传送门<br>读取多人玩家最后保存位置<br>从玩家列表跨维度定位 |

## 小范围三维预览

V0.6 可以从二维地图选择 `1x1`、`3x3`、`5x5` 或 `8x8` 区块范围，在后台生成简化三维场景，并在独立窗口中查看。左键拖动旋转，右键拖动平移旋转中心，滚轮缩放，`R` 恢复默认视图，`Esc` 关闭三维窗口。当前使用简化颜色和立方体几何，原版纹理与更完整的视觉资源属于后续版本范围。

## 快速开始

1. 打开 [Releases](https://github.com/huang3337/MC-World-Explorer/releases) 页面自行选择版本，推荐下载带有 `Latest` 标记的最新版 Windows x64 便携版 ZIP。
2. 将 ZIP 完整解压到普通文件夹。
3. 双击 `MC World Explorer.exe`。
4. 点击“选择 Minecraft 目录...”，选择游戏目录、存档目录、整合包实例或单个世界。

请保留 EXE、`app` 和 `runtime` 的原有目录结构，不要单独移动 EXE，也不要直接在压缩包内运行。

> [!NOTE]
> 软件包尚未进行代码签名。Windows SmartScreen 可能显示安全提示，请从本仓库的正式 Release 下载，并核对发布页提供的 SHA-256。

## 存档安全

程序只读访问以下 Minecraft 存档数据：

- `level.dat`
- `playerdata/*.dat`
- `icon.png`
- 目录元数据
- 各维度的 Region 文件

程序不会修改、移动或删除 Minecraft 存档。日志、缩略图和地图块缓存、导出图片及配置分别保存在程序根目录的 `logs/`、`cache/`、`exports/` 和 `config/`。

对于重要存档，仍建议保持正常的备份习惯。

## 从源码运行

需要安装 JDK 21。在 Windows PowerShell 或命令提示符中进入项目根目录后运行：

```powershell
.\gradlew.bat run
```

首次构建需要下载 Gradle 和项目依赖。应用日志位于项目根目录 `logs/mc-world-explorer.log`；运行数据位于同一根目录下的 `cache/`、`exports/` 和 `config/`。

## 项目文档

- [项目路线图](PROJECT_ROADMAP.md)
- [常见问题](docs/FAQ.md)
- [方块颜色规则](docs/方块颜色规则.md)
- [V0.1.1 开发与验收记录](docs/progress/V0.1.1.md)
- [V0.2 开发与验收记录](docs/progress/V0.2.md)
- [V0.2.1 开发与验收记录](docs/progress/V0.2.1.md)
- [V0.3 开发与验收记录](docs/progress/V0.3.md)
- [V0.3.1 开发与验收记录](docs/progress/V0.3.1.md)
- [V0.6 开发与验收记录](docs/progress/V0.6.md)
- [V0.4 三维技术可行性验证记录](docs/progress/V0.4.md)
- [重大开发决策](docs/decisions/)

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。
