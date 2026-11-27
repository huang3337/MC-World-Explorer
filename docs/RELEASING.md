# 上传与发布

本文件仅描述构建、检查和发布流程。现行代码版本、提交与发布状态、验证结论统一见 [CURRENT](progress/CURRENT.md)。源码检查点不等于正式发行；提交、推送、标签及 Release 分别单独确认后执行。

## 源码上传与分支合入前

1. 核对 [当前状态](progress/CURRENT.md) 的最终审查、自动测试与已知限制。
2. 执行 `gradlew.bat check assembleSdkDist verifyCoreIsolation`。默认不启动人工布局测试；跳过项目不能计作通过。
3. 检查 Git 变更与忽略规则。只上传源码、正式工具、测试与文档；不要上传 build、测试存档、缓存、插件安装库、截图日志或仓库外延期材料。
4. 确认应用没有预装插件，当前代码和包不含延期专用适配，且 `:mc-world-explorer-sdk` 通过零 JavaFX/LWJGL 字节码纯净度校验。
5. 日常开发在 `dev` 分支实施，完成审阅、测试与文档同步后提交并合入 `main` 分支（关联对应 GitHub Issue，例如在提交说明或 PR 中注明 `Closes #1`），再核对版本标签与 Release。

## SDK 分发产物

构建 `:mc-world-explorer-sdk` 离线分发包与本地 Maven 构件：

```powershell
.\gradlew.bat assembleSdkDist :mc-world-explorer-sdk:publishToMavenLocal
```

- `build/core-0.8.1/sdk-dist/` 输出 `mc-world-explorer-sdk-<version>.jar`、`-sources.jar` 与 `-javadoc.jar`，可直接作为 GitHub/Gitee Release 附件分发。
- 仓库根目录 `jitpack.yml` 配置了 JDK 21 下的 `:mc-world-explorer-sdk:publishToMavenLocal -x test`，版本标签推送后即可由 JitPack 构建并供外部 Gradle/Maven 项目引用（用法见 [SDK 使用指南](SDK_GUIDE.md)）。

## Windows 包

使用 Windows x64 与 JDK 21（包含 jpackage）：

```powershell
powershell -ExecutionPolicy Bypass -File tools/package-windows.ps1
```

已有依赖缓存时可加 `-Offline`。同一源码已完成测试时可加 `-SkipTests`；该开关不产生测试通过证据。输出已存在时，检查后使用 `-Replace`，仅替换该候选包、展开目录与校验文件，不清空 build。

默认输出在 `build/windows-package/`，包含应用 ZIP、SHA-256 与展开目录。输入仅来自当前 `installDist`，不读取用户安装库或仓库外材料。保留自带 Java（含 `jdk.crypto.ec` 以支持便携版 HTTPS 更新检查）、LICENSE 和使用说明。打包脚本不启动界面、不上传文件。

保留上一版展开目录时，可指定独立输出目录，例如 `-OutputName windows-package-0.7.5`。

## 正式发布前

- 核对最终包中 EXE 与主 JAR 的版本、便携说明、许可证和校验文件；从解压副本启动并检查本版主要功能。
- 应用版本以当前 build.gradle 和包内元数据为准，重新测试和构建；不得只重命名旧 ZIP。兼容测试中的历史版本号不随发布号机械替换。
- 检查最终 Windows 包身份、零预装状态、SHA-256 与启动结果。原生启动及操作的人工验收结果单独记录。
- 正式发布时准备对应版本的发布说明并核对已知限制；[0.7.4 发布说明](releases/V0.7.4.md)保留为历史记录。不把未知 Mod 支持、未测性能或条件跳过写成通过。
- 完成发布检查后创建标签、推送和发布。发布完成再把 README 的下载状态更新为真实 Release。
