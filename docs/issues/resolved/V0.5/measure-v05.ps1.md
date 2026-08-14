# 代码审查：measure-v05.ps1

- **审查日期**：2026-08-04
- **审查工具**：Codex
- **审查范围**：V0.5 文件 `scripts/v05/measure-v05.ps1`
- **问题总数**：2 个（严重 1 / 高 0 / 中 1 / 低 0）

### ISSUE-V05-SCRIPT-001：输出路径可经 junction 绕回 Minecraft 存档

- **严重程度**：严重
- **类别**：安全性
- **文件**：`scripts/v05/measure-v05.ps1`
- **行号**：审查时 25-33
- **状态**：已修复

**问题描述**：初版只做规范化字符串前缀比较，随后立即创建输出目录和清单。

**当前代码（审查时快照）**：

```powershell
if ($outputPath.StartsWith($worldPath + [System.IO.Path]::DirectorySeparatorChar, ...)) {
    throw ...
}
New-Item -ItemType Directory -Force -Path $outputPath
```

**问题分析**：攻击性或误配置的 junction 可让表面位于项目内的输出实际写入存档，破坏最严格只读边界。

**建议修改**：创建任何文件前拒绝输出路径现有祖先中的重解析点。

**影响范围**：Minecraft 存档完整性。

- **解决日期**：2026-08-04
- **实际修改**：新增逐祖先 `ReparsePoint` 检查。
- **验证证据**：junction 回绕测试退出码 1，目标世界文件数保持 0。

### ISSUE-V05-SCRIPT-002：隐藏子进程失败日志丢失且退出码可能为空

- **严重程度**：中
- **类别**：错误处理
- **文件**：`scripts/v05/measure-v05.ps1`
- **行号**：审查时 104-119
- **状态**：已修复

**问题描述**：`Start-Process` 未保存 stdout/stderr；加入重定向后，在当前 PowerShell 环境中返回的 `ExitCode` 仍可能为空。

**当前代码（审查时快照）**：

```powershell
$process = Start-Process -FilePath $java -PassThru -WindowStyle Hidden
$process.WaitForExit()
```

**问题分析**：失败样本只显示退出 1，无法区分维度不存在、区块缺失或零几何。

**建议修改**：使用显式 `.NET Process` 同步读取两路输出、持久化日志并可靠取得退出码。

**影响范围**：真实验证可诊断性和证据保留。

- **解决日期**：2026-08-04
- **实际修改**：保存 `stdout.log`、`stderr.log`，失败时回显 stderr 和明确退出码。
- **验证证据**：Aether 全空气样本显示逐区块 SUCCESS 但零几何，并以退出码 1 明确失败。
