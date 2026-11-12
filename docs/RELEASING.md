# 上传与发布

本文件仅描述构建、检查和发布流程。现行代码版本、提交与发布状态、验证结论统一见 [CURRENT](progress/CURRENT.md)。源码检查点不等于正式发行；提交、推送、标签及 Release 分别按明确授权执行。

## 源码上传前

1. 核对 [当前状态](progress/CURRENT.md) 的最终审查、自动测试与已知限制。
2. 执行 `gradlew.bat test verifyCoreIsolation`。默认不启动人工布局测试；跳过项目不能计作通过。
3. 检查 Git 变更与忽略规则。只上传源码、正式工具、测试与文档；不要上传 build、测试存档、缓存、插件安装库、截图日志或仓库外延期材料。
4. 确认应用没有预装插件，当前代码和包不含延期专用适配。
5. 完成变更审阅和上述检查后创建提交，再核对版本标签与GitHub Release。

## Windows 包

使用 Windows x64 与 JDK 21（包含 jpackage）：

```powershell
powershell -ExecutionPolicy Bypass -File tools/package-windows.ps1
```

已有依赖缓存时可加 `-Offline`。同一源码已完成测试时可加 `-SkipTests`；该开关不产生测试通过证据。输出已存在时，检查后使用 `-Replace`，仅替换该候选包、展开目录与校验文件，不清空 build。

默认输出在 `build/windows-package/`，包含应用 ZIP、SHA-256 与展开目录。输入仅来自当前 `installDist`，不读取用户安装库或仓库外材料。保留自带 Java、LICENSE 和使用说明。打包脚本不启动界面、不上传文件。

## 正式发布前

- 核对最终包包含关于页面、新图标与选区修复；这些更新已经纳入0.7.4。
- 应用版本以当前 build.gradle 和包内元数据为准，重新测试和构建；不得只重命名旧 ZIP。兼容测试中的历史版本号不随发布号机械替换。
- 检查最终 Windows 包身份、零预装状态、SHA-256 与启动结果。原生启动及操作的人工验收结果单独记录。
- 正式发布时准备对应版本的发布说明并核对已知限制；[0.7.4 发布说明](releases/V0.7.4.md)保留为历史记录。不把未知 Mod 支持、未测性能或条件跳过写成通过。
- 完成发布检查后创建标签、推送和发布。发布完成再把 README 的下载状态更新为真实 Release。
