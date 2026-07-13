# MC World Explorer 首版 README 设计规范

## 目标

为已经发布 V0.1.1 的仓库建立中文首页，使访问者能够快速理解项目定位、下载并运行便携版，同时明确项目仍在持续开发。

首版保持轻量，不承担完整功能手册或开发历史记录的职责。详细功能展示、界面截图和后续版本介绍留待后续开发补充。

## 视觉方向

采用已确认的“简洁发布型”布局：

- 顶部居中展示透明像素图标。
- 图标下方显示项目名 `MC World Explorer`。
- 使用一句话说明“只读的 Minecraft Java 版存档浏览工具”。
- 展示 V0.1.1、Windows x64、Java 21 和 GPL-3.0 状态徽章。
- 提供“下载最新版本”和“查看开发路线”两个主要入口。

页面不使用复杂横幅、动态效果或与项目无关的装饰图片。

## 内容结构

1. **项目简介**：说明项目用于在不启动 Minecraft 的情况下查看世界存档基础信息。
2. **V0.1.1 功能**：仅概述当前版本已经完成的扫描、基础信息展示、整合包兼容、目录选择和后台只读处理。
3. **快速开始**：说明下载 ZIP、完整解压并运行 `MC World Explorer.exe`；提醒不能单独移动 EXE。
4. **存档安全**：明确当前只读访问 `level.dat`、`icon.png` 和文件元数据，不修改、移动或删除存档。
5. **开发状态**：说明 V0.1.1 是当前稳定版本，项目仍在持续开发；不把 V0.2 及后续计划写成现有功能。
6. **项目路线图**：链接仓库根目录的 `PROJECT_ROADMAP.md`，供希望了解后续计划的访问者查阅。
7. **源码运行**：简要列出 Java 21 要求和 Windows 下的 `gradlew.bat run` 命令。
8. **许可证**：链接 `LICENSE` 并标注 GPL-3.0。

## 文件安排

- 新建根目录 `README.md`。
- 将现有透明像素图标复制为 `docs/assets/mc-world-explorer.png`，供 README 使用。
- 将视觉设计临时目录 `.superpowers/` 加入 `.gitignore`，不提交本地对比页面。
- 不修改现有 V0.1.1 Release 和发布附件。

## 链接

- 最新版本：`https://github.com/huang3337/MC-World-Explorer/releases/latest`
- V0.1.1：`https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.1.1`
- 路线图：`PROJECT_ROADMAP.md`
- 许可证：`LICENSE`

## 验收标准

- GitHub 能正常显示图标、徽章和全部相对链接。
- 下载入口指向当前正式 Release。
- README 只描述 V0.1.1 已实现能力，不跨版本宣传。
- 页面明确说明项目仍在持续开发。
- 快速开始和存档安全说明与软件包内 README 一致。
- `.superpowers/` 和 `packaging/` 均不进入 Git。
