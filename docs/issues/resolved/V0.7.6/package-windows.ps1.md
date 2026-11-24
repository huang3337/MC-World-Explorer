# 代码审查：package-windows.ps1

- **审查日期**：2026-11-24
- **审查工具**：Gemini
- **审查范围**：Windows 便携包打包输入路径与校验脚本
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-PACKAGEWINDOWS-003：打包脚本硬编码 `core-0.7.5` 构建目录

- **严重程度**：🟡 中
- **类别**：代码质量（构建与打包一致性）
- **文件**：`tools/package-windows.ps1`
- **行号**：46, 72, 73（审查时快照）
- **状态**：已修复

**问题描述**：
`tools/package-windows.ps1` 的重解析点预检、`installDist` 库目录与 `runtime-files.json` 清单路径硬编码指向 `core-0.7.5`，与递增后的 `build/core-0.7.6` 不一致。

**当前代码**（审查时快照）：
```powershell
foreach ($tree in @((Join-Path $buildRoot 'core-0.7.5'), $outputRoot)) {
...
    $inputDir = Join-Path $projectRoot 'build\core-0.7.5\install\MC-World-Explorer\lib'
    $expected = Get-Content -LiteralPath (Join-Path $buildRoot 'core-0.7.5\packaging\runtime-files.json') -Raw -Encoding UTF8 | ConvertFrom-Json
```

**问题分析**：
构建输出切换至 `build/core-0.7.6` 后，旧脚本会读取缺失或过时的 `core-0.7.5` 目录，导致打包失败或校验错位。

**建议修改**：
将三处目录名同步更新为 `core-0.7.6`。

**影响范围**：
本地 Windows x64 便携包构建与输入哈希核对。

- **解决日期**：2026-11-24
- **实际修改**：已将三处 `core-0.7.5` 更新为 `core-0.7.6`。
- **验证证据**：脚本路径与 `build.gradle` 的 `layout.buildDirectory` 逐项对齐。
