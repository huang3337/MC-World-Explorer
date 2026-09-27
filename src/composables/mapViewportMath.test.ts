import { describe, expect, it } from "vitest";
import { displayZoomLabel, isTemporaryScale, nearestDisplayZoom, tileZoom, visibleTiles, wheelVisualZoom, zoomAt } from "./mapViewportMath";

describe("Java 地图视口数学", () => {
  it("光标锚定缩放保持指向的世界坐标不变", () => {
    const before = { centerX: -40, centerZ: 90, blocksPerPixel: 2 };
    const worldX = before.centerX + (700 - 400) * before.blocksPerPixel;
    const worldZ = before.centerZ + (100 - 300) * before.blocksPerPixel;
    const after = zoomAt(before, .5, 700, 100, 800, 600);
    expect(after.centerX + (700 - 400) * after.blocksPerPixel).toBe(worldX);
    expect(after.centerZ + (100 - 300) * after.blocksPerPixel).toBe(worldZ);
  });

  it("七档显示缩放复用五档瓦片并按负坐标计算可见范围", () => {
    expect([.25, .5, 1, 2, 4, 8, 16].map(tileZoom)).toEqual([1, 1, 1, 2, 4, 8, 16]);
    expect(nearestDisplayZoom(.36)).toBe(.5);
    const tiles = visibleTiles({ centerX: -1, centerZ: -1, blocksPerPixel: 1 }, 256, 256, 1);
    expect(tiles.map(tile => [tile.tileX, tile.tileZ])).toEqual(expect.arrayContaining([[-1, -1], [0, 0]]));
    expect(tiles[0]).toMatchObject({ tileX: -1, tileZ: -1 });
  });

  it("滚轮先执行 Java 等价的连续视觉缩放，未过阈值时不切换瓦片倍率", () => {
    const visual = wheelVisualZoom(2, 100);
    expect(visual).toBeCloseTo(2 * Math.pow(2, 100 / 240));
    expect(tileZoom(nearestDisplayZoom(visual))).toBe(2);
    expect(wheelVisualZoom(.25, -120)).toBe(.25);
    expect(wheelVisualZoom(16, 120)).toBe(16);
  });
});

it("稳定的像素放大保持锐利并明确区分显示与数据倍率", () => {
  expect(isTemporaryScale(0.5, 0.5)).toBe(false);
  expect(isTemporaryScale(0.63, 0.5)).toBe(true);
  expect(displayZoomLabel(0.25)).toBe("显示：4 像素/方块；数据：1 方块/像素");
  expect(displayZoomLabel(0.5)).toBe("显示：2 像素/方块；数据：1 方块/像素");
  expect(displayZoomLabel(4)).toBe("显示与数据：4 方块/像素");
  expect(displayZoomLabel(2.5, 2)).toBe("显示：2.50 方块/像素；数据：2 方块/像素");
});
