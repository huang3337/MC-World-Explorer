# 二维地图明暗处理

## ISSUE-MAPTILERENDER-001 缩略图与地图瓦片分别维护相同的 RGB 运算

2026-11-19 · 低 · 代码质量 · 已修复。

[MapTileRenderer.java](../../../../src/main/java/com/mcworldexplorer/map/MapTileRenderer.java) 原第 106 行与 [PreviewRenderer.java](../../../../src/main/java/com/mcworldexplorer/preview/PreviewRenderer.java) 原第 93 行分别定义 `shade(int color, double factor)`。两者对每个 RGB 分量乘以亮度、四舍五入并限制在 0–255；任一侧单独修改都会造成显示差异。

两条渲染路径共用 `BlockColorPalette.shade`，各自仍负责计算邻域高度差、选择缺失颜色和写入像素。两个渲染器的回归通过，同批真实存档的 26 张地表和高度带瓦片与基线逐张核对一致。
