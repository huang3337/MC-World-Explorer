# 客户端颜色绑定

## ISSUE-CLRCOL-002 已解析的原版模型缺少新版世界颜色绑定

2026-11-19 确认 1 项中等严重程度的三维外观问题，0.7.5 工作区已修复并完成真实存档回归。影响带 tintindex 的模型表面，几何和原始纹理仍可读取。

[ClientBlockColors.java](../../../../src/main/java/com/mcworldexplorer/voxel/resource/ClientBlockColors.java) 原先用 `Set.of("1.20.1","1.21.1").contains(version)` 判定已核对的注册协议。[SavedBiomeColors.java](../../../../src/main/java/com/mcworldexplorer/voxel/resource/SavedBiomeColors.java) 原先只核对了旧版群系颜色输入。

通过实例资源与实际网格构建复现。1.21.5 的区块 (0, 0) 诊断列出草方块、樱花树叶、粉红色花簇和矮草。26.3 的区块 (0, 7) 诊断列出草方块。26.3 客户端的 `BlockColors.register` 接受由 `BlockTintSource` 组成的有序列表，世界颜色、群系采样及数据包优先级均需验证。

### 修复与验证

[颜色层声明](../../../../src/main/resources/model-protocols/vanilla-tint-layers.json)按已核实的客户端注册语义提供图层到颜色来源的映射。[颜色解析器](../../../../src/main/java/com/mcworldexplorer/voxel/resource/BlockTintResolver.java)在模型选定后读取这些图层。保存的种子、维度高度、区段群系和实例混合半径决定取样位置，有效群系定义及资源包色表决定 RGB。26.3 的独立世界生成设置已接入资源变更检测。灰度 PNG 按原始样本参与着色，保留透明度。

1.21.5 的 (0, 0) 生成 9,687 面。樱花树林草色为 `#B6DB61`，草方块顶面、矮草和粉红色花簇的茎使用该色，花瓣、樱叶和泥土保留原纹理。26.3 的 (0, 7) 生成 2,712 面。平原草方块从有效色表取得 `#91BD59`，泥土底面保留原纹理。两个选区的 `UNBOUND_TINT` 均为零。[回归测试](../../../../src/test/java/com/mcworldexplorer/voxel/resource/VanillaVegetationTintTest.java)比对最终图集像素，另覆盖跨群系混合和缺失群系单元。负载对照见[当前进度](../../../progress/CURRENT.md)。
