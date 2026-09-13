# DECISION-016：Rust NBT 基础采用 fastnbt 与 flate2

- **状态**：已确认
- **确认日期**：2026-09-06
- **确认人**：项目开发者
- **适用范围**：`tauri-rewrite` 的 `mcwe-core` NBT 与 gzip/zlib 解析基础

## 背景

自行实现完整 NBT 与压缩解析会扩大长度、递归、数组和解压攻击的安全责任；直接向领域层暴露动态 NBT 树又会造成长期库类型耦合。

## 最终选择

- `mcwe-core` 使用 `fastnbt` 解析 NBT，使用 `flate2` 处理 gzip 和 zlib。
- 库类型、Serde DTO 和压缩 reader 保持在核心内部，公开领域 API 不暴露 `fastnbt::Value` 或可写 NBT 接口。
- 所有数据继续通过 `WorldSource` / `WorldFile` 只读边界获取。
- 输入、解压输出、嵌套深度和领域集合必须分别受限，不将未受限的第三方分配引入核心。

## 原因与影响

这一组合复用成熟的 Minecraft NBT 解析与 Rust 压缩基础，同时由项目自己维持资源、错误、取消和只读边界。扩展新压缩或历史存档格式时，仍需根据当前兼容性决策确认范围。
