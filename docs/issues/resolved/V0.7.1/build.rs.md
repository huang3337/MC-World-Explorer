# 代码审查：build.rs

- **审查日期**：2026-09-04
- **审查工具**：Codex
- **审查范围**：Tauri mock runtime 测试的 Windows 链接支持
- **问题总数**：1 个（🔴 0 / 🟠 1 / 🟡 0 / 🟢 0）

### ISSUE-TAURIBUILD-001：mock 测试缺少 Common Controls v6 清单而无法启动

- **严重程度**：🟠 高
- **类别**：代码质量
- **文件**：`src-tauri/build.rs`
- **行号**：审查时第 1–3 行
- **状态**：已修复

**问题描述**：
新增 mock IPC 测试编译成功，但测试程序在测试入口前返回 `0xc0000139 / STATUS_ENTRYPOINT_NOT_FOUND`；沙箱内外均复现。

**当前代码**（审查时快照）：
```rust
fn main() {
    tauri_build::build();
}
```

**问题分析**：
dumpbin 显示测试 EXE 导入 `comctl32.dll!TaskDialogIndirect` 且没有资源节；本机 System32 版本不导出该入口。Tauri 构建源码及 Microsoft 文档要求通过应用清单选择 Common Controls v6；原构建没有将相应资源带入 lib 单元测试 EXE。

**建议修改**：
在 Windows 目标中添加 `/MANIFEST:EMBED` 和仅声明 Common Controls v6 的 `/MANIFESTDEPENDENCY` 链接参数，不替换系统 DLL、不提升应用权限。

**影响范围**：
Tauri mock runtime 测试启动；需同时回归应用 lib/bin 构建，防止清单重复冲突。main 的 Java V0.6 不受影响。

**参考**：[Microsoft MANIFESTDEPENDENCY](https://learn.microsoft.com/en-us/cpp/build/reference/manifestdependency-specify-manifest-dependencies?view=msvc-170)。

**解决日期**：2026-09-04。

**实际修改**：Windows 链接使用 `/MANIFEST:EMBED` 和 Common Controls v6 依赖声明；bin 目标额外使用 `/MANIFEST:NO`，保留 Tauri 已通过 `resource.lib` 嵌入的完整清单，不再生成第二份。首轮只添加通用参数时，lib mock 测试通过，但 bin 测试暴露 CVT1100 重复 MANIFEST / LNK1123；最终按目标区分处理，未替换 DLL、安装组件或改变系统设置。

**验证证据**：5 项 mock IPC 测试通过；`cargo test --workspace --locked --offline` 的 20 项应用测试、10 项普通权限核心测试、2 项编译失败文档测试通过，bin 测试入口正常退出；2 项既有符号链接专项明确忽略。`cargo build -p mc-world-explorer --bin mc-world-explorer --locked --offline`、格式检查、全目标 Clippy 和前端构建通过。未启动真实应用窗口或执行发布打包，完整 GUI 验收仍在后续门禁。
