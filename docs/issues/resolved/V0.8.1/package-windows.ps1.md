# 代码审查：package-windows.ps1

- **审查日期**：2026-11-27
- **审查工具**：Gemini
- **审查范围**：双模块拆分后的 Windows 便携包主 JAR 筛选逻辑
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-PACKAGEWINDOWS-004：Windows 大小写不敏感通配符将 `mc-world-explorer-sdk-*.jar` 误计为主应用 JAR 导致打包失败

- **严重程度**：🟠 高
- **类别**：功能缺陷（构建与打包）
- **文件**：`tools/package-windows.ps1`
- **行号**：90–91（审查时快照）
- **状态**：已修复

**问题描述**：
双模块拆分后，`:mc-world-explorer-app` 的运行时依赖包含 `mc-world-explorer-sdk-0.8.1.jar`。`tools/package-windows.ps1` 使用 `Get-ChildItem -Filter 'MC-World-Explorer-*.jar'` 查找唯一主 JAR，因 Windows 文件系统通配符不区分大小写，同时命中了 `MC-World-Explorer-0.8.1.jar` 与 `mc-world-explorer-sdk-0.8.1.jar`，触发 `Expected exactly one current application JAR.` 中断打包。

**当前代码**（审查时快照）：
```powershell
$mainJars = @(Get-ChildItem -LiteralPath $inputDir -Filter 'MC-World-Explorer-*.jar' -File)
if ($mainJars.Count -ne 1) { throw 'Expected exactly one current application JAR.' }
```

**问题分析**：
`-Filter` 在 NTFS 上按大小写不敏感前缀匹配，`MC-World-Explorer-*` 会匹配所有 `mc-world-explorer-*` 子模块构件，必须增加区分大小写且紧接语义化版本号的精确文件名正则过滤。

**建议修改**：
在 `Get-ChildItem` 之后通过 `Where-Object { $_.Name -cmatch '\AMC-World-Explorer-\d+\.\d+\.\d+(-SNAPSHOT)?\.jar\z' }` 精确筛选桌面端主 JAR，并在 `ModalOverlayContractTest` 增加契约断言。

**影响范围**：
Windows x64 便携包 `jpackage` 构建流程。

- **解决日期**：2026-11-27
- **实际修改**：增加 `-cmatch '\AMC-World-Explorer-\d+\.\d+\.\d+(-SNAPSHOT)?\.jar\z'` 精确筛选主 JAR，并在 `ModalOverlayContractTest` 补充回归断言。
- **验证证据**：`ModalOverlayContractTest` 与 `tools/package-windows.ps1 -Offline -SkipTests -Replace -OutputName windows-package-0.8.1` 执行通过。
