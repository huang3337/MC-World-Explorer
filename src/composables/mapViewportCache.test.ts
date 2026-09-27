import { expect, it } from "vitest";
import { trimViewportVisuals } from "./mapViewportCache";

it("缓存满时保留当前可见目标，优先淘汰旧区域画面", () => {
  const tiles = new Map(Array.from({ length: 129 }, (_, index) => [`tile-${index}`, index] as const));
  trimViewportVisuals(tiles, new Set(["tile-0", "tile-128"]), 128);
  expect(tiles.size).toBe(128);
  expect(tiles.has("tile-0")).toBe(true);
  expect(tiles.has("tile-1")).toBe(false);
  expect(tiles.has("tile-128")).toBe(true);
});
