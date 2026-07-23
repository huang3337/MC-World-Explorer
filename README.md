<p align="center">
  <img src="docs/assets/mc-world-explorer.png" alt="MC World Explorer 图标" width="144">
</p>

<h1 align="center">MC World Explorer</h1>

<p align="center">
  只读的 Minecraft Java 版存档浏览工具
</p>

<p align="center">
  <a href="https://github.com/huang3337/MC-World-Explorer/releases/latest"><img src="https://img.shields.io/github/v/release/huang3337/MC-World-Explorer?label=Release&color=2f81f7" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Windows-x64-0078D4?logo=windows" alt="Windows x64">
  <img src="https://img.shields.io/badge/Java-21-E76F00?logo=openjdk&logoColor=white" alt="Java 21">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-2da44e" alt="GPL-3.0 许可证"></a>
</p>

<p align="center">
  <strong><a href="https://github.com/huang3337/MC-World-Explorer/releases/latest">下载最新版本</a></strong>
  ·
  <strong><a href="PROJECT_ROADMAP.md">查看项目路线图</a></strong>
</p>

## 关于项目

MC World Explorer 用于在不启动 Minecraft 的情况下，快速查看本地世界存档的基础信息。

当前稳定版本为 **V0.2.1**，在存档扫描和信息展示基础上，加入主世界、下界、末地和可识别 Mod 维度的缩略图预览、地表总览、32 格洞穴高度带、分层缓存与 PNG 导出。后续计划请查阅[项目路线图](PROJECT_ROADMAP.md)。

## V0.1.1 功能

- 扫描 Minecraft Java 版原版存档和版本隔离的整合包存档
- 支持选择 `.minecraft`、`saves`、`versions`、单个整合包实例或单个世界目录
- 显示世界名称、版本、游戏模式、时间、种子、出生点、玩家位置和世界图标
- 支持读取较大的整合包 `level.dat`
- 在后台扫描存档，并显示加载、结果数量、空目录和失败状态
- 仅以只读方式访问存档，不提供编辑或写入功能

## V0.2.1 功能

- 围绕主世界个人重生点、世界出生点或原点生成俯视缩略图
- 只读解析标准 `.mca` Region、现代 Section 和 Palette 数据
- 生成并在程序内显示 `512 x 512` PNG 世界预览
- 发现主世界、下界、末地和真实样本可验证的 Mod 维度
- 通过地表总览按钮和平滑高度滑块按需切换 32 格洞穴高度带，并为各维度独立恢复本次运行中的选择
- 按世界、维度和图层隔离并复用缩略图缓存
- 自动复用程序根目录 `cache/` 中的有效缓存
- 一键导出当前预览到程序根目录 `exports/`
- 日志、缓存、导出和配置全部保存在程序根目录，不再写入 AppData 或 Windows Preferences

## 快速开始

1. 从 [GitHub Releases](https://github.com/huang3337/MC-World-Explorer/releases/latest) 下载 Windows x64 便携版 ZIP。
2. 将 ZIP 完整解压到普通文件夹。
3. 双击 `MC World Explorer.exe`。
4. 点击“选择 Minecraft 目录...”，选择需要浏览的游戏目录、存档目录或世界。

软件包已经内置 Java 21 运行时，无需另行安装 Java。请保留 EXE、`app` 和 `runtime` 的原有目录结构，不要单独移动 EXE，也不要直接在压缩包内运行。

> [!NOTE]
> 软件包尚未进行代码签名。Windows SmartScreen 可能显示安全提示，请从本仓库的正式 Release 下载并核对发布页提供的 SHA-256。

## 存档安全

当前源码仅以只读方式访问 `level.dat`、`icon.png`、目录元数据和 `region/*.mca`，不会修改、移动或删除 Minecraft 存档，也不读取或写入 `playerdata/*.dat`。

程序生成的日志、缩略图缓存、导出图片和配置分别保存在程序根目录的 `logs/`、`cache/`、`exports/` 和 `config/`。对于重要存档，仍建议保持正常的备份习惯。

## 开发状态

MC World Explorer **仍在持续开发中**。V0.2.1 已完成世界缩略图、多维度预览、32 格洞穴高度带、缓存和 PNG 导出，并通过自动化测试、真实存档只读验收、代码审查、人工界面验收和 Windows x64 便携包验证。V0.3 的地图拖动、缩放、坐标定位和标记尚未开始源码开发。

- [查看完整项目路线图](PROJECT_ROADMAP.md)
- [查看 V0.1.1 开发与验收记录](docs/progress/V0.1.1.md)
- [查看 V0.2 开发与验收记录](docs/progress/V0.2.md)
- [查看 V0.2.1 开发与验收记录](docs/progress/V0.2.1.md)
- [下载 V0.2.1](https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.2.1)

## 从源码运行

需要安装 JDK 21。在 Windows PowerShell 或命令提示符中进入项目根目录后运行：

```powershell
.\gradlew.bat run
```

首次构建需要下载 Gradle 和项目依赖。应用日志位于项目根目录 `logs/mc-world-explorer.log`；运行数据位于同一根目录下的 `cache/`、`exports/` 和 `config/`。

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。
