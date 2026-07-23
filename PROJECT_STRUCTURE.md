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
│  ├─ issues/                     活动问题、编号清单和问题记录规范
│  │  └─ resolved/                各版本已解决问题与逐文件验算记录
│  ├─ decisions/                  已确认的重大技术决策
│  └─ superpowers/specs/          已确认或待确认的功能设计规范
└─ src/
   ├─ main/
   │  ├─ java/com/mcworldexplorer/
   │  │  ├─ App.java              JavaFX 应用入口
   │  │  ├─ nbt/                  level.dat 解析
   │  │  ├─ preview/              维度发现、图层请求、地表/高度带采样、PNG、缓存与导出
   │  │  ├─ region/               Region 文件头、区块定位与基础解压
   │  │  ├─ storage/              严格便携的程序根目录、运行数据路径与配置
   │  │  ├─ ui/                   控制器和存档树 UI
   │  │  └─ world/                存档模型、扫描和游戏模式
   │  └─ resources/               FXML、CSS、日志和界面文本
   └─ test/java/com/mcworldexplorer/
      ├─ nbt/                     NBT 单元测试与只读集成测试
      ├─ preview/                 中心点、采样、渲染、缓存、导出和真实存档验收
      ├─ region/                  Region 合成测试与真实文件只读验收
      ├─ storage/                 便携路径、配置、目录写入和布局识别测试
      ├─ ui/                      树节点测试
      └─ world/                   模型和扫描测试
```

`docs/decisions/` 已记录严格便携存储、Region 兼容边界和统一维度图层系统三项重大决策。
V0.2/V0.2.1 使用 `storage/PortablePaths.java` 将日志、缓存、导出和配置定位到程序根目录
的 `logs/`、`cache/`、`exports/` 和 `config/`。这些目录受 `.gitignore` 保护，不属于
源码目录，也不得写入 Minecraft 存档目录。当前多维度预览继续复用同一便携缓存边界。
