import { describe, expect, it } from "vitest";
import {
  MAX_CANVAS_EDGE,
  canvasSize,
  cellFromClient,
  writeSurfacePixels,
} from "./useSurfaceCanvas";
import type { SurfaceData } from "../protocol/surface-v1";

describe("Canvas 尺寸和坐标", () => {
  it("按 CSS 尺寸和非整数 DPR 建立 backing store，并限制最大边长", () => {
    expect(canvasSize(576, 432, 1)).toEqual({ width: 576, height: 432 });
    expect(canvasSize(576, 432, 1.25)).toEqual({ width: 720, height: 540 });
    expect(canvasSize(20_000, 10_000, 4)).toEqual({
      width: MAX_CANVAS_EDGE,
      height: MAX_CANVAS_EDGE,
    });
    expect(canvasSize(0, Number.NaN, Number.POSITIVE_INFINITY)).toEqual({ width: 144, height: 144 });
  });

  it("使用 CSS 内容矩形映射四角、中心和边界外坐标", () => {
    const rect = { left: 10, top: 20, width: 288, height: 144 };
    expect(cellFromClient(rect, 10, 20)).toEqual({ x: 0, z: 0 });
    expect(cellFromClient(rect, 154, 92)).toEqual({ x: 72, z: 72 });
    expect(cellFromClient(rect, 298, 164)).toEqual({ x: 143, z: 143 });
    expect(cellFromClient(rect, -100, 500)).toEqual({ x: 0, z: 143 });
    expect(cellFromClient({ ...rect, width: 0 }, 10, 20)).toBeNull();
  });


  it("按 X 先递增、再沿 Z 换行写入颜色，并为失败状态使用固定视觉键", () => {
    const colors = new Uint32Array(144 * 144);
    const statuses = new Uint8Array(144 * 144);
    colors[0] = 0x112233;
    colors[1] = 0x445566;
    colors[144] = 0x778899;
    statuses[143] = 1;
    statuses[144 * 143] = 2;
    statuses[144 * 144 - 1] = 3;
    const data = { colors, statuses, heights: new Int32Array(144 * 144), metadata: {} } as SurfaceData;
    const pixels = new Uint8ClampedArray(144 * 144 * 4);
    writeSurfacePixels(data, pixels);
    expect([...pixels.slice(0, 12)]).toEqual([17, 34, 51, 255, 68, 85, 102, 255, 0, 0, 0, 255]);
    expect([...pixels.slice(144 * 4, 144 * 4 + 4)]).toEqual([119, 136, 153, 255]);
    expect([...pixels.slice(143 * 4, 143 * 4 + 4)]).toEqual([43, 49, 56, 255]);
    expect([...pixels.slice(144 * 143 * 4, 144 * 143 * 4 + 4)]).toEqual([102, 80, 31, 255]);
    expect([...pixels.slice(-4)]).toEqual([109, 40, 52, 255]);
    expect(() => writeSurfacePixels(data, new Uint8ClampedArray(4))).toThrow("INVALID_SURFACE_PIXEL_BUFFER");
  });
});


it("扩大地图在不同 CSS 尺寸下仍映射精确的 1024 方块坐标", () => {
  for (const edge of [288, 576, 1024]) {
    const rect = { left: 10, top: 20, width: edge, height: edge };
    expect(cellFromClient(rect, 10 + edge / 2, 20 + edge / 2, 1024)).toEqual({ x: 512, z: 512 });
    expect(cellFromClient(rect, 10 + edge, 20 + edge, 1024)).toEqual({ x: 1023, z: 1023 });
  }
});
