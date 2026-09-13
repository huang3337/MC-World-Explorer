import { describe, expect, it } from "vitest";
import { nearestDisplayZoom, tileZoom, visibleTiles, zoomAt } from "./mapViewportMath";

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
});
