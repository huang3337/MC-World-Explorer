# MC World Explorer 项目结构

```text
MC-World-Explorer/
├─ PROJECT_CONTEXT.md             项目背景、愿景和用户场景
├─ PROJECT_ROADMAP.md             版本范围、当前阶段和后续路线
├─ DEVELOPMENT_RULES.md           本地 AI 开发与代码审查规则，不进入 Git
├─ PROJECT_STRUCTURE.md           当前项目结构说明
├─ build.gradle                   Gradle 构建配置
├─ settings.gradle                Gradle 项目配置
├─ gradlew / gradlew.bat          Gradle Wrapper 启动脚本
├─ gradle/wrapper/                Gradle Wrapper 配置与运行文件
├─ run-app.bat                    Windows 应用启动脚本
├─ docs/
│  ├─ progress/                   各版本开发进度与验收记录
│  ├─ issues/                     活动问题和问题记录规范
│  │  └─ resolved/V0.1.1/         V0.1.1 已解决问题与验算记录
│  ├─ decisions/                  已确认的重大技术决策
│  └─ superpowers/specs/          已确认或待确认的功能设计规范
└─ src/
   ├─ main/
   │  ├─ java/com/mcworldexplorer/
   │  │  ├─ App.java              JavaFX 应用入口
   │  │  ├─ nbt/                  level.dat 解析
   │  │  ├─ preview/              缩略图中心点模型与决策逻辑
   │  │  ├─ storage/              严格便携的程序根目录和运行数据路径
   │  │  ├─ ui/                   控制器和存档树 UI
   │  │  └─ world/                存档模型、扫描和游戏模式
   │  └─ resources/               FXML、CSS、日志和界面文本
   └─ test/java/com/mcworldexplorer/
      ├─ nbt/                     NBT 单元测试与只读集成测试
      ├─ preview/                 中心点规则和真实存档只读验收
      ├─ storage/                 便携路径、目录写入和布局识别测试
      ├─ ui/                      树节点测试
      └─ world/                   模型和扫描测试
```

`docs/decisions/DECISION-001.md` 已记录严格便携的本地数据存储决策。V0.2 已使用
`storage/PortablePaths.java` 将日志定位到程序根目录的 `logs/`；计划使用的 `cache/`、
`exports/` 和未来的 `config/` 同样属于程序运行数据，受 `.gitignore` 保护，不属于
源码目录，也不得写入 Minecraft 存档目录。
