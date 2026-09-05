# 代码审查：world_source.rs

- **审查日期**：2026-09-04
- **审查工具**：Codex
- **审查范围**：V0.7.1 Task 6 Windows 目录句柄只读边界
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

### ISSUE-WSOURCE-001：仅属性访问的目录句柄未形成预期共享保护

- **严重程度**：🟠 高
- **类别**：安全性
- **文件**：`crates/mcwe-core/src/world_source.rs`
- **行号**：审查时约 181–184 行，`open_checked` 的目录访问模式分支
- **状态**：已修复

**问题描述**：
初始实现对目录指定 `FILE_READ_ATTRIBUTES`，试图结合只读共享保护目录。实际测试发现目录仍能被删除，无法满足逐级防替换要求。

**当前代码**（审查时快照）：
```rust
if directory {
    options.access_mode(FILE_READ_ATTRIBUTES);
}
```

**问题分析**：
只查询属性不等价于请求实际读取访问；不能据此假定读、写、删除共享保护生效。`directory_guards_prevent_replacement_and_release_on_drop` 在 `remove_dir(&child).is_err()` 断言处失败。

**建议修改**：
```rust
// 目录同样保留 read(true) 的实际读取访问，不覆盖为仅属性查询。
options.read(true).share_mode(FILE_SHARE_READ);
```

**影响范围**：
Windows 世界根和中间目录的并发防替换保护。代码尚未提交或发布，不影响 main 的 V0.6。

- **解决日期**：2026-09-04。
- **实际修改**：移除仅属性访问覆盖，目录同样使用实际读取访问及 `FILE_SHARE_READ`；不请求写权限、不降级重试。
- **验证证据**：最终 `directory_guards_prevent_replacement_and_release_on_drop` 在另一线程尝试删除子目录和重命名父目录均被拒绝，释放保护后重命名成功；工作区回归通过，提升权限完整测试 12/12 通过。

### ISSUE-WSOURCE-002：junction 测试路径不应进入 PowerShell 命令文本

- **严重程度**：🟡 中
- **类别**：代码质量
- **文件**：`crates/mcwe-core/src/world_source.rs`
- **行号**：审查时约 447–450 行，`rejects_junction_escape` 的进程调用
- **状态**：已修复

**问题描述**：
测试把工作树派生的路径作为 PowerShell `-Command` 后的参数传递。该模式由 PowerShell 继续解释命令文本，不能保证迁移到含特殊字符的工作树路径后仍按纯数据处理。

**当前代码**（审查时快照）：
```rust
.args(["-Command", "& { param($link, $target) New-Item -ItemType Junction -Path $link -Target $target }"])
.arg(&junction).arg(&outside)
```

**问题分析**：
这仅影响测试夹具，不是产品 Shell 能力。应避免将路径作为命令语法解释，尤其该专项测试可能在提升权限环境执行。

**建议修改**：
```rust
.env("MCWE_TEST_LINK", &junction)
.env("MCWE_TEST_TARGET", &outside)
// 固定命令从子进程环境读取路径值；不修改父进程环境。
```

**影响范围**：
junction 合成测试的路径兼容和测试运行边界，不影响 main 或生产只读 API。

- **解决日期**：2026-09-04。
- **实际修改**：固定 PowerShell 命令通过子进程专用环境变量读取路径，不把路径拼入命令源码；父进程环境不变。
- **验证证据**：`rejects_junction_escape` 使用包含空格、单引号、`&` 和中文的世界及 junction 名称，在普通权限和提升权限测试中均通过；全 workspace Clippy `-D warnings` 通过。
