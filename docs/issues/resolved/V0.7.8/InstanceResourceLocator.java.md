# 代码审查：InstanceResourceLocator.java

- **审查日期**：2026-11-25
- **审查范围**：`mods/<mcversion>` 版本子目录扫描的符号链接与重解析点越界防护
- **问题总数**：1 个（🔴 0 / 🟠 0 / 🟡 1 / 🟢 0）

### ISSUE-IRLOC-005：`mods/<mcversion>` 子目录扫描未校验重解析点与真实路径前缀

- **严重程度**：🟡 中
- **类别**：安全性
- **文件**：`src/main/java/com/mcworldexplorer/voxel/resource/InstanceResourceLocator.java`
- **行号**：60–65（审查时快照）
- **状态**：已修复

**问题描述**：
扫描 `mods/<mcversion>`（如 `mods/1.7.10`）一级子目录时，仅使用 `NOFOLLOW_LINKS` 检查 `attrs.isDirectory()`，未排除 Windows 目录联接（Junction / `isOther()`）及校验 `toRealPath().startsWith(realMods)`。

**当前代码**（审查时快照）：
```java
var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
if(attrs.isDirectory())subDirs.add(path);
```

**问题分析**：
Windows NTFS 目录联接在 `NOFOLLOW_LINKS` 下仍可能报告 `isDirectory() == true` 并指向实例目录外部。实例资源定位器对资源包目录已执行 `toRealPath().startsWith(...)` 校验，`mods/<mcversion>` 子目录应保持同一边界。

**建议修改**：
同时要求 `!attrs.isSymbolicLink() && !attrs.isOther()`，并解析 `realSub = path.toRealPath()` 确认 `realSub.startsWith(realMods)` 后再纳入扫描。

**影响范围**：
`InstanceResourceLocator` 对 `mods/<mcversion>/*.jar` 子目录的受控枚举。

- **解决日期**：2026-11-25
- **实际修改**：补齐 `mods/<mcversion>` 子目录的符号链接/`isOther` 过滤与 `toRealPath().startsWith(realMods)` 越界校验。
- **验证证据**：通过 `InstanceResourceSourceTest`、`PreFlatteningResourcePipelineTest` 及本机 1.7.10 实例 `mods/1.7.10` 只读集成测试。
