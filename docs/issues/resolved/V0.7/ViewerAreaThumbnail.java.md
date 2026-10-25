# 代码审查：ViewerAreaThumbnail.java

- **审查日期**：2026-10-25
- **审查工具**：Codex
- **审查范围**：开发者反馈的二维缩略图颜色来源
- **问题总数**：1 个（严重 0 / 高 0 / 中 1 / 低 0）

### ISSUE-VTHUMB-001：地图缩略图使用三维 FALLBACK 配色污染 Mod 方块

- **严重程度**：🟡 中
- **类别**：UI 健壮性
- **文件**：src/main/java/com/mcworldexplorer/viewer/ViewerAreaThumbnail.java
- **行号**：原 surfaceColor 方法（已移除）；现 fromArea 委托二维渲染器
- **状态**：已修复

**问题描述**：原缩略图虽从原始区块俯视取样，并非三维截图，但调用三维 BlockClassifier；Mod 名称被统一替换为紫色，无法代表二维地图的实际配色。

**当前代码**（审查时快照）：
```java
var block = BlockClassifier.classify(sections.get(index).blockState(x, y, z));
if (!block.isAir()) return 0xff000000 | block.rgb();
```

**问题分析**：二维 BlockColorPalette 可为跨 namespace 名称归类配色，而三维对非 minecraft: 名称使用 FALLBACK。复用后者违反开发者期望的二维地图缩略图语义，也缺少二维聚合和高度阴影。

**建议修改**：独立于三维材料，复用二维 SurfaceSampler、BlockColorPalette 和 MapTileRenderer；按目标矩形裁剪缩小。

**影响范围**：区域列表中包含 Mod 方块的缩略图、二维与缩略图视觉一致性。

- **解决日期**：2026-10-25
- **实际修改**：新增 MapAreaThumbnailRenderer，复用同一原始区块与已读取 halo，经二维地表取样/名称配色/全局两格网格及高度阴影生成图片，再等比缩小。ViewerAreaThumbnail 不再依赖 BlockClassifier，不读取三维材质/网格或截图，也不再次读取存档。
- **验证证据**：ViewerAreaThumbnailTest 的 example:grass_block 在三维保持 0xE83EED，而缩略图使用 BlockColorPalette 正常色；全量及真实 test001 地图缩略图/删除检查通过，源清单/大小/mtime/SHA-256 不变。

## 最终复审与完整增量验算


- **审查日期**：2026-10-25
- **审查工具**：Codex
- **审查范围**：V0.7 已加载区域列表专项；从已读取的体素数据生成有界俯视缩略图
- **本增量待修复问题数**：0 个（严重 0 / 高 0 / 中 0 / 低 0）
- **状态**：增量审查无待修复项；版本尚未整体验收

## 六维验算

- **错误处理**：不可解码的区块沿用二维地图缺失底色，等比留白与无数据区分；取消每行检查并抛出取消异常，协调器不发布过期结果。
- **资源管理**：固定 160×100 ARGB、64,000 字节；不读文件、不启动线程，不复制网格；原始区块只经二维地表流程，不重复读取文件。
- **安全性**：按已验证 target 宽深取样，限制输出像素尺寸与数组长度；构造和访问像素均复制，防止外部修改缓存。
- **代码质量**：北朝上、矩形等比留白，复用二维地表取样、名称配色、像素聚合和高度阴影；不使用示例图冒充真实区域。
- **模块耦合**：仅消费只读 VoxelAreaLoadResult，无 JavaFX 和窗口依赖；不会再次扫描存档。
- **UI 健壮性**：单测覆盖跨 namespace 正常二维配色、最高非空气表面、负坐标矩形、左右方向、等比留白、缺区块、取消和像素不可变；真实样本另启用。

验证范围与证据见 [V0.7 进度](../../../progress/V0.7.md)。窗口替身和离屏检查不替代原生 GL 帧率及桌面验收。

此前三维配色污染问题已修复，快照及验证见 [ISSUE-VTHUMB-001](ViewerAreaThumbnail.java.md)，当前没有待修复项。

## V0.7 最终收口复核

2026-10-25 按开发者确认的范围完成最终复审与文档收口。本文件此前的阶段状态保留时点语境；本增量未发现未解决问题。自动化/只读证据与未执行的原生测量边界见 [V0.7 最终收口](../../../progress/V0.7.md)。
