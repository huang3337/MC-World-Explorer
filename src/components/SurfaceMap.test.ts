import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, type App as VueApp } from "vue";
import type { SurfaceData } from "../protocol/surface-v1";
import type { ChunkRect } from "../services/types";
import SurfaceMap from "./SurfaceMap.vue";

let app: VueApp | undefined;
let getContextSpy: { mockRestore(): void };
let rectSpy: { mockRestore(): void };
let createImageData: ReturnType<typeof vi.fn>;
let fillText: ReturnType<typeof vi.fn>;

const context = {
  imageSmoothingEnabled: true,
  fillStyle: "",
  strokeStyle: "",
  lineWidth: 1,
  font: "",
  textAlign: "start",
  textBaseline: "alphabetic",
  beginPath: vi.fn(),
  moveTo: vi.fn(),
  lineTo: vi.fn(),
  stroke: vi.fn(),
  clearRect: vi.fn(),
  drawImage: vi.fn(),
  fillRect: vi.fn(),
  strokeRect: vi.fn(),
  putImageData: vi.fn(),
  setTransform: vi.fn(),
  createImageData: vi.fn(),
  fillText: vi.fn(),
};

function makeSurface(failedChunk?: { x: number; z: number; status: 1 | 2 | 3 }): SurfaceData {
  const colors = new Uint32Array(144 * 144).fill(0x123456);
  const heights = new Int32Array(144 * 144).fill(72);
  const statuses = new Uint8Array(144 * 144);
  if (failedChunk) {
    for (let localZ = 0; localZ < 16; localZ += 1) {
      const row = (failedChunk.z * 16 + localZ) * 144 + failedChunk.x * 16;
      statuses.fill(failedChunk.status, row, row + 16);
      heights.fill(0, row, row + 16);
    }
  }
  return {
    colors,
    heights,
    statuses,
    metadata: {
      sessionId: 1,
      taskId: 2,
      originBlockX: -96,
      originBlockZ: 48,
      centerChunkX: -2,
      centerChunkZ: 7,
      centerX: -31,
      centerY: 70,
      centerZ: 112,
      centerSource: "playerRespawn",
      worldSpawn: { x: -16, y: 72, z: 120 },
      successfulChunks: failedChunk ? 80 : 81,
      failedChunks: failedChunk ? 1 : 0,
      warnings: [],
    },
  };
}

function pointer(type: string, x: number, y: number, id = 7): Event {
  const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button: 0 });
  Object.defineProperty(event, "pointerId", { value: id });
  return event;
}

function mount(data: SurfaceData, selections: ChunkRect[] = [], disabled = false): HTMLElement {
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(defineComponent({
    setup: () => () => h(SurfaceMap, { data, disabled, onSelect: (rect: ChunkRect) => selections.push(rect) }),
  }));
  app.mount(host);
  return host;
}

beforeEach(() => {
  createImageData = vi.fn((_width: number, _height: number) => ({ data: new Uint8ClampedArray(_width * _height * 4) }));
  fillText = vi.fn();
  Object.assign(context, { createImageData, fillText, imageSmoothingEnabled: true });
  getContextSpy = vi.spyOn(HTMLCanvasElement.prototype, "getContext")
    .mockImplementation(() => context as unknown as CanvasRenderingContext2D);
  rectSpy = vi.spyOn(HTMLCanvasElement.prototype, "getBoundingClientRect")
    .mockImplementation(() => ({ left: 10, top: 20, width: 288, height: 288, right: 298, bottom: 308, x: 10, y: 20, toJSON: () => ({}) }));
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  getContextSpy.mockRestore();
  rectSpy.mockRestore();
});

it("使用双 Canvas、最近邻 backing store 和真实 DOM 图例，不创建每格节点", async () => {
  const host = mount(makeSurface({ x: 4, z: 4, status: 1 }));
  await nextTick();
  const canvases = host.querySelectorAll("canvas");
  expect(canvases).toHaveLength(2);
  expect(canvases[0].width).toBe(288);
  expect(canvases[1].height).toBe(288);
  expect(context.imageSmoothingEnabled).toBe(false);
  expect(createImageData).toHaveBeenCalledTimes(1);
  expect(host.querySelectorAll("*").length).toBeLessThan(30);
  expect(host.textContent).toContain("缺失");
  expect(host.textContent).toContain("当前选区：区块 X -3..-1，Z 6..8，3×3");
  expect(host.textContent).toContain("缺失 1");
  expect(fillText).toHaveBeenCalledWith("○", 72, 72);
});

it("悬停按 CSS 矩形映射负世界坐标，且不重建基础 ImageData", async () => {
  const host = mount(makeSurface());
  await nextTick();
  const overlay = host.querySelector(".surface-overlay") as HTMLCanvasElement;
  expect(createImageData).toHaveBeenCalledTimes(1);
  overlay.dispatchEvent(pointer("pointermove", 10, 20));
  await nextTick();
  expect(host.textContent).toContain("世界 X -96，Z 48");
  expect(host.textContent).toContain("表面 Y 72");
  expect(createImageData).toHaveBeenCalledTimes(1);
  globalThis.dispatchEvent(new Event("resize"));
  await nextTick();
  expect(createImageData).toHaveBeenCalledTimes(1);
});

it("指针只定位，显式生成提交预设选区", async () => {
  const selections: ChunkRect[] = [];
  const host = mount(makeSurface(), selections);
  await nextTick();
  const overlay = host.querySelector(".surface-overlay") as HTMLCanvasElement;
  overlay.dispatchEvent(pointer("pointerdown", 90, 116));
  overlay.dispatchEvent(pointer("pointermove", 290, 300));
  overlay.dispatchEvent(pointer("pointerup", 290, 300));
  await nextTick();
  expect(selections).toEqual([]);
  (host.querySelector("button") as HTMLButtonElement).click();
  expect(selections).toEqual([{ minX: -5, minZ: 5, width: 3, depth: 3 }]);
});

it("保留显示模式移出焦点顺序并拒绝指针和键盘提交", async () => {
  const selections: ChunkRect[] = [];
  const host = mount(makeSurface(), selections, true);
  await nextTick();
  const figure = host.querySelector(".surface-map") as HTMLElement;
  const overlay = host.querySelector(".surface-overlay") as HTMLCanvasElement;
  expect(figure.getAttribute("aria-disabled")).toBe("true");
  expect(overlay.tabIndex).toBe(-1);
  overlay.dispatchEvent(pointer("pointerdown", 10, 20));
  overlay.dispatchEvent(pointer("pointerup", 10, 20));
  overlay.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  expect(selections).toEqual([]);
  expect(host.textContent).toContain("当前视图仅保留显示");
});

it("键盘移动保留预设大小并用 Enter 触发三维生成", async () => {
  const selections: ChunkRect[] = [];
  const host = mount(makeSurface(), selections);
  await nextTick();
  const overlay = host.querySelector(".surface-overlay") as HTMLCanvasElement;
  overlay.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowLeft", bubbles: true }));
  overlay.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowUp", shiftKey: true, bubbles: true }));
  overlay.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await nextTick();
  expect(selections).toEqual([{ minX: -4, minZ: 5, width: 3, depth: 3 }]);
  expect(host.textContent).toContain("方向键移动");
  expect(overlay.getAttribute("aria-describedby")).toContain("surface-selection");
});
it("1024 底图非区块对齐边缘保持 8×8，且能选择旧范围以外位置", async () => {
  const data = { ...makeSurface(), colors: new Uint32Array(1024 * 1024),
    heights: new Int32Array(1024 * 1024), statuses: new Uint8Array(1024 * 1024) };
  data.metadata.originBlockX = -513; data.metadata.originBlockZ = -495;
  data.metadata.centerX = -1; data.metadata.centerZ = 17;
  data.metadata.centerChunkX = -1; data.metadata.centerChunkZ = 1;
  const selections: ChunkRect[] = [];
  const host = mount(data, selections); await nextTick();
  const select = host.querySelector("select")!;
  expect(select.value).toBe("3");
  const canvas = host.querySelector(".surface-overlay")!;
  for (const size of [1, 3, 5, 8]) {
    select.value = String(size); select.dispatchEvent(new Event("change", { bubbles: true }));
    await nextTick();
    canvas.dispatchEvent(pointer("pointerdown", 298, 308)); await nextTick();
    (host.querySelector("button") as HTMLButtonElement).click();
    expect(selections[selections.length - 1]).toEqual({ minX: 31 - size, minZ: 33 - size, width: size, depth: size });
  }
  canvas.dispatchEvent(pointer("pointerdown", 10, 20)); await nextTick();
  (host.querySelector("button") as HTMLButtonElement).click();
  expect(selections[selections.length - 1]).toEqual({ minX: -32, minZ: -30, width: 8, depth: 8 });
});
