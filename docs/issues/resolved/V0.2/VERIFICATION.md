# V0.2 代码审查与验算汇总

- **审查日期**：2026-07-18
- **审查工具**：Codex
- **审查范围**：V0.2 修改或新增的 Java、FXML、CSS、Logback 和消息资源
- **审查记录**：32 个文件
- **问题总数**：2 个（🔴 0 / 🟠 0 / 🟡 2 / 🟢 0）
- **最终状态**：2 个问题均已修复，活动问题目录无 V0.2 待办项

## 已修复问题

| 编号 | 严重程度 | 文件 | 结果 |
|---|---|---|---|
| `ISSUE-CONTROLLER-010` | 🟡 中 | `MainController.java` | 缓存命中后不再在缩略图中央重复显示状态文字 |
| `ISSUE-CONTROLLER-011` | 🟡 中 | `MainController.java` | 自定义目录改存程序根目录 `config/settings.properties`，停止写 Windows Preferences |

## 自动化验收

- 默认 `gradlew.bat clean test`：20 个测试套件、81 个测试，75 个通过、6 个真实目录测试按设计跳过，0 个失败、0 个错误。
- 设置 `MCWORLD_TEST_VERSIONS_DIR=D:\MC\.minecraft\versions` 和 `MCWORLD_PREVIEW_TEST_WORLD=D:\MC\.minecraft\versions\ArdaCraft\saves\新的世界`：20 个测试套件、81 个测试全部通过，0 个跳过、0 个失败、0 个错误。
- 真实目录测试覆盖版本隔离扫描、`level.dat`、中心点、Region 解压、地表采样和完整缩略图生成，并校验相关存档文件在测试前后未发生变化。
- PNG 导出测试覆盖安全文件名、重复编号、精确字节复制、临时文件清理、源文件缺失、默认目录失败、已有目标保护和世界目录拒绝。
- 便携配置测试覆盖缺失配置、中文路径往返、原子替换、临时文件清理和配置目录冲突。

## 启动与边界验收

- 实际执行 Gradle `run` 后，JavaFX 应用持续运行，日志记录程序根目录为项目根目录。
- FXML、控制器和新增导出按钮没有加载异常；启动标准错误为空。
- 验收结束后，由本次检查启动的 Gradle 和 JavaFX 进程均已退出。
- 生产代码不再引用 `java.util.prefs.Preferences`；AppData 仅用于定位 Minecraft 默认读取目录。
- 生产写入点限定在程序根目录的 `logs/`、`cache/`、`exports/` 和 `config/`。
- `cache/`、`logs/`、`exports/`、`config/`、`packaging/` 和 `DEVELOPMENT_RULES.md` 均由 Git 忽略，不进入仓库。
- `git diff --check` 通过，未发现遗留 `TODO`、`FIXME` 或 `TBD`。

## 已知非阻塞事项

- Gradle 8.7 仍报告构建脚本使用了将在 Gradle 9.0 不兼容的弃用特性；当前编译、测试和运行不受影响。
- 当前自动化环境无法点击桌面 JavaFX 控件，因此导出按钮的人工点击和备用 `FileChooser` 交互留作软件包审查项；底层导出行为和 FXML 绑定均已自动化或实际启动验证。
- 旧版本曾写入的 Windows Preferences 注册表值不会由 V0.2 自动删除，避免程序擅自清理用户系统数据；V0.2 不再读取或写入该值。
