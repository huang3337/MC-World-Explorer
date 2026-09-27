import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, ref, type App as VueApp } from "vue";
import { invoke } from "@tauri-apps/api/core";
import type { MapViewportTarget, WorldSummary } from "../services/types";
import { visibleTiles } from "../composables/mapViewportMath";
import MapViewport from "./MapViewport.vue";

let app: VueApp | undefined;
let contextSpy: { mockRestore(): void };
let rectSpy: { mockRestore(): void; mockReturnValue(value: DOMRect): void };
const context = {
  imageSmoothingEnabled: false, fillStyle: "", strokeStyle: "", lineWidth: 1,
  setTransform: vi.fn(), fillRect: vi.fn(), strokeRect: vi.fn(), drawImage: vi.fn(),
  beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), putImageData: vi.fn(),
  createImageData: vi.fn(() => ({ data: new Uint8ClampedArray(256 * 256 * 4) })),
};
const world: WorldSummary = { sessionId: 7, name: "合成世界", displayPath: "D:\\World", dataVersion: null, centerX: 0, centerY: 64, centerZ: 0, centerSource: "worldSpawn", worldSpawn: { x: 0, y: 64, z: 0 }, loadAnchor: { x: 24, y: 70, z: -16 }, loadAnchorSource: "playerExit" };

function batch(sequence: number, kind: "partial" | "failed", tileX = 0, viewportTaskId = 9): ArrayBuffer {
  const partial = kind === "partial", itemCount = 1, cells = partial ? 1 : 0;
  const metadata = new TextEncoder().encode(JSON.stringify({ sessionId: 7, viewportTaskId, batchSequence: sequence, itemCount, terminalItemCount: partial ? 0 : 1, hasMore: false, state: partial ? "items" : "ended" }));
  const descriptorOffset = 96, colorsOffset = 176, heightsOffset = colorsOffset + cells * 4, statusesOffset = heightsOffset + cells * 4, metadataOffset = (statusesOffset + cells + 3) & ~3;
  const buffer = new ArrayBuffer(metadataOffset + metadata.length), view = new DataView(buffer), bytes = new Uint8Array(buffer);
  bytes.set([77, 67, 87, 69]); view.setUint16(4, 1, true); view.setUint16(6, 4, true); view.setUint32(8, 32, true); view.setUint32(12, buffer.byteLength, true); view.setUint32(16, metadataOffset, true); view.setUint32(20, metadata.length, true); view.setUint32(24, 32, true); view.setUint32(28, 4, true);
  for (const [index, id, scalar, offset, length, count] of [[0,20,4,descriptorOffset,80,20],[1,21,4,colorsOffset,cells*4,cells],[2,22,5,heightsOffset,cells*4,cells],[3,23,1,statusesOffset,cells,cells]] as const) { const at = 32 + index * 16; view.setUint16(at, id, true); view.setUint16(at + 2, scalar, true); view.setUint32(at + 4, offset, true); view.setUint32(at + 8, length, true); view.setUint32(at + 12, count, true); }
  const word = (index: number, value: number) => view.setUint32(descriptorOffset + index * 4, value, true);
  word(0, partial ? 1 : 3); word(1, tileX >>> 0); word(2, 0xffffffff); word(3, 2); if (partial) { word(4, 1); word(8, 1); word(9, 1); word(10, 1); word(11, 1024); word(17, 1); view.setUint32(colorsOffset, 0x123456, true); view.setInt32(heightsOffset, 64, true); } else { word(18, 1); }
  bytes.set(metadata, metadataOffset); return buffer;
}

interface ReadyOptions {
  sessionId?: number; viewportTaskId?: number; tileX?: number; tileZ?: number; zoom?: 1 | 2 | 4 | 8 | 16;
  successfulChunks?: number; missingChunks?: number; unsupportedChunks?: number; corruptChunks?: number;
  state?: "items" | "ended";
}

function stoppedBatch(state: "cancelled" | "shuttingDown"): ArrayBuffer {
  const metadata = new TextEncoder().encode(JSON.stringify({ sessionId: 7, viewportTaskId: 9, batchSequence: 2, itemCount: 0, terminalItemCount: 0, hasMore: false, state }));
  const buffer = new ArrayBuffer(96 + metadata.length), view = new DataView(buffer);
  new Uint8Array(buffer).set([77, 67, 87, 69]);
  view.setUint16(4, 1, true); view.setUint16(6, 4, true);
  for (const [offset, value] of [[8, 32], [12, buffer.byteLength], [16, 96], [20, metadata.length], [24, 32], [28, 4]]) view.setUint32(offset, value, true);
  for (const [index, id, scalar] of [[0, 20, 4], [1, 21, 4], [2, 22, 5], [3, 23, 1]]) {
    const at = 32 + index * 16;
    view.setUint16(at, id, true); view.setUint16(at + 2, scalar, true); view.setUint32(at + 4, 96, true);
  }
  new Uint8Array(buffer).set(metadata, 96);
  return buffer;
}

it.each(["cancelled", "shuttingDown"] as const)("%s 批次结束加载并保留局部画面，只有取消允许重试", async state => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  let starts = 0, polls = 0;
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") { starts += 1; return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] }; }
    if (command === "next_map_viewport_batch") { polls += 1; return starts > 1 ? readyBatch() : polls === 1 ? batch(1, "partial") : stoppedBatch(state); }
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.querySelector('[role="status"]')?.textContent).toContain(state === "cancelled" ? "当前视口加载已取消" : "应用正在关闭"));
  expect(context.putImageData).toHaveBeenCalledTimes(1);
  expect(polls).toBe(2);
  expect(host.textContent).not.toContain("个瓦片进入终态");
  const retry = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "重试当前视口");
  if (state === "cancelled") {
    host.querySelector("canvas")!.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight" }));
    await new Promise(resolve => setTimeout(resolve, 120));
    expect(starts).toBe(1);
    expect(retry).toBeDefined(); retry!.click();
    await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
    expect(starts).toBe(2);
  } else {
    expect(retry).toBeUndefined();
    host.querySelector("canvas")!.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight" }));
    await new Promise(resolve => setTimeout(resolve, 120));
    expect(starts).toBe(1);
  }
});

it.each((["TASK_CANCELLED", "TASK_OUTDATED", "SESSION_OUTDATED", "SHUTTING_DOWN"] as const).flatMap(code =>
  (["start", "batch"] as const).map(at => ({ code, at })),
))("当前任务 $at 的 $code 错误进入明确停止状态", async ({ code, at }) => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") {
      if (at === "start") throw { code };
      return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    }
    if (command === "next_map_viewport_batch") throw { code };
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  const expected = { TASK_CANCELLED: "加载已取消", TASK_OUTDATED: "加载任务已过期", SESSION_OUTDATED: "请重新选择世界", SHUTTING_DOWN: "应用正在关闭" }[code];
  await vi.waitFor(() => expect(host.querySelector('[role="status"]')?.textContent).toContain(expected));
  expect(host.querySelector('[role="alert"]')).toBeNull();
  expect(host.textContent?.includes("重试当前视口")).toBe(code === "TASK_CANCELLED" || code === "TASK_OUTDATED");
  if (at === "batch") expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 });
  else expect(vi.mocked(invoke).mock.calls.some(call => call[0] === "cancel_map_viewport")).toBe(false);
});

it.each(["TASK_CANCELLED", "SHUTTING_DOWN"] as const)("旧世界迟到的 %s 不改变新世界的完整状态", async code => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  const activeWorld = ref(world);
  let rejectOld!: (reason: unknown) => void;
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    const sessionId = (args as { sessionId?: number } | undefined)?.sessionId;
    if (command === "start_map_viewport") return { sessionId, viewportTaskId: sessionId === 7 ? 9 : 10, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return sessionId === 7 ? new Promise<ArrayBuffer>((_resolve, reject) => { rejectOld = reject; }) : readyBatch({ sessionId: 8, viewportTaskId: 10 });
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world: activeWorld.value }) })); app.mount(host);
  await vi.waitFor(() => expect(rejectOld).toBeDefined());
  activeWorld.value = { ...world, sessionId: 8 };
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
  rejectOld({ code });
  await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 }));
  expect(host.querySelector('[role="status"]')?.textContent).toBe("当前视口已完整加载");
  expect(invoke).not.toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 8, viewportTaskId: 10 });
});

it("世界会话过期后重置视图不重试，切换世界才重新启动", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  const activeWorld = ref(world);
  let starts = 0;
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    const sessionId = (args as { sessionId?: number } | undefined)?.sessionId;
    if (command === "start_map_viewport") {
      starts += 1;
      if (sessionId === 7) throw { code: "SESSION_OUTDATED" };
      return { sessionId: 8, viewportTaskId: 10, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    }
    if (command === "next_map_viewport_batch") return readyBatch({ sessionId: 8, viewportTaskId: 10 });
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world: activeWorld.value }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("请重新选择世界"));
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "重置视图")!.click();
  await new Promise(resolve => setTimeout(resolve, 120));
  expect(starts).toBe(1);
  activeWorld.value = { ...world, sessionId: 8 };
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
  expect(starts).toBe(2);
});

function readyBatch(options: ReadyOptions = {}): ArrayBuffer {
  const sessionId = options.sessionId ?? 7, viewportTaskId = options.viewportTaskId ?? 9;
  const tileX = options.tileX ?? 0, tileZ = options.tileZ ?? -1, zoom = options.zoom ?? 2;
  const totalChunks = (16 * zoom) ** 2, pixelsPerChunk = (16 / zoom) ** 2, cells = 256 * 256;
  const successful = options.successfulChunks ?? totalChunks, missing = options.missingChunks ?? 0;
  const unsupported = options.unsupportedChunks ?? 0, corrupt = options.corruptChunks ?? 0;
  if (successful + missing + unsupported + corrupt !== totalChunks) throw new Error("bad synthetic chunk counts");
  const metadata = new TextEncoder().encode(JSON.stringify({ sessionId, viewportTaskId, batchSequence: 1, itemCount: 1, terminalItemCount: 1, hasMore: false, state: options.state ?? "ended" }));
  const descriptorOffset = 96, colorsOffset = 176, heightsOffset = colorsOffset + cells * 4, statusesOffset = heightsOffset + cells * 4, metadataOffset = statusesOffset + cells;
  const buffer = new ArrayBuffer(metadataOffset + metadata.length), view = new DataView(buffer), bytes = new Uint8Array(buffer);
  bytes.set([77, 67, 87, 69]); view.setUint16(4, 1, true); view.setUint16(6, 4, true); view.setUint32(8, 32, true); view.setUint32(12, buffer.byteLength, true); view.setUint32(16, metadataOffset, true); view.setUint32(20, metadata.length, true); view.setUint32(24, 32, true); view.setUint32(28, 4, true);
  for (const [index, id, scalar, offset, length, count] of [[0,20,4,descriptorOffset,80,20],[1,21,4,colorsOffset,cells*4,cells],[2,22,5,heightsOffset,cells*4,cells],[3,23,1,statusesOffset,cells,cells]] as const) { const at = 32 + index * 16; view.setUint16(at, id, true); view.setUint16(at + 2, scalar, true); view.setUint32(at + 4, offset, true); view.setUint32(at + 8, length, true); view.setUint32(at + 12, count, true); }
  const word = (index: number, value: number) => view.setUint32(descriptorOffset + index * 4, value, true);
  word(0, 2); word(1, tileX >>> 0); word(2, tileZ >>> 0); word(3, zoom); word(8, 256); word(9, 256); word(10, totalChunks); word(11, totalChunks); word(12, successful); word(13, missing); word(14, unsupported); word(15, corrupt); word(17, cells);
  const colors = new Uint32Array(buffer, colorsOffset, cells), statuses = new Uint8Array(buffer, statusesOffset, cells);
  colors.fill(0x123456, 0, successful * pixelsPerChunk);
  let offset = successful * pixelsPerChunk;
  for (const [status, count] of [[1, missing], [2, unsupported], [3, corrupt]] as const) { statuses.fill(status, offset, offset + count * pixelsPerChunk); offset += count * pixelsPerChunk; }
  bytes.set(metadata, metadataOffset); return buffer;
}

beforeEach(() => {
  contextSpy = vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(context as unknown as CanvasRenderingContext2D);
  rectSpy = vi.spyOn(HTMLCanvasElement.prototype, "getBoundingClientRect").mockReturnValue({ left: 0, top: 0, width: 800, height: 520, right: 800, bottom: 520, x: 0, y: 0, toJSON: () => ({}) });
});
afterEach(() => { app?.unmount(); app = undefined; contextSpy.mockRestore(); rectSpy.mockRestore(); vi.unstubAllGlobals(); });

it("世界挂载后直接启动视口，串行提交局部结果和终态失败", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return vi.mocked(invoke).mock.calls.filter(call => call[0] === "next_map_viewport_batch").length === 1 ? batch(1, "partial") : batch(2, "failed");
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已收束但不完整：1 个瓦片请求失败"));
  expect(invoke).toHaveBeenCalledWith("start_map_viewport", { sessionId: 7, centerX: 24, centerZ: -16, viewportWidth: 1, viewportHeight: 1, blocksPerPixel: 2, displayBlocksPerPixel: 2 });
  expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "next_map_viewport_batch")).toHaveLength(2);
  expect(context.putImageData).toHaveBeenCalled();
  const putCalls = vi.mocked(context.putImageData).mock.calls;
  const partialImage = putCalls[putCalls.length - 1]?.[0];
  expect(partialImage?.data[7]).toBe(0);
  expect(host.textContent).toContain("深灰为未加载");
  const retry = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "重试当前视口");
  expect(retry).toBeDefined();
  retry?.click();
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")).toHaveLength(2));
});

it("多瓦片视口先提交局部画面，不等待任何完整瓦片或逐块请求", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  const boundaryWorld = { ...world, loadAnchor: { ...world.loadAnchor, x: 511 } };
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return {
      sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 2,
      targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }, { tileX: 1, tileZ: -1, blocksPerPixel: 2 }],
    };
    if (command === "next_map_viewport_batch") return vi.mocked(invoke).mock.calls.filter(call => call[0] === "next_map_viewport_batch").length === 1
      ? batch(1, "partial") : new Promise(() => undefined);
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world: boundaryWorld }) })); app.mount(host);
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "next_map_viewport_batch")).toHaveLength(2));
  expect(context.putImageData).toHaveBeenCalled();
  expect(context.drawImage).toHaveBeenCalled();
  expect(host.textContent).toContain("当前视口：0/2 个瓦片进入终态");
  expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")).toHaveLength(1);
  expect(vi.mocked(invoke).mock.calls.some(call => call[0] === "load_map_tile")).toBe(false);
});

it("后端遗漏当前可见目标时不能显示完整，且可重试", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  const boundaryWorld = { ...world, loadAnchor: { ...world.loadAnchor, x: 511 } };
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return {
      sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1,
      targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }],
    };
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world: boundaryWorld }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口请求失败，可重试"));
  expect(host.textContent).not.toContain("当前视口已完整加载");
  expect(vi.mocked(invoke).mock.calls.some(call => call[0] === "next_map_viewport_batch")).toBe(false);
  expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 });
});

it("同一数据倍率内的滚轮缩放保留视觉结果且不重启视口", async () => {
  const targets: MapViewportTarget[] = visibleTiles({ centerX: 24, centerZ: -16, blocksPerPixel: 2 }, 800, 520, 2);
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: targets.length, targets };
    if (command === "next_map_viewport_batch") return new Promise(() => undefined);
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")).toHaveLength(1));

  host.querySelector("canvas")?.dispatchEvent(new WheelEvent("wheel", { deltaY: -100, clientX: 400, clientY: 260, cancelable: true }));
  await new Promise(resolve => setTimeout(resolve, 220));

  expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")).toHaveLength(1);
  expect(host.textContent).toContain("数据：2 方块/像素");
});

it("拖动进入新瓦片时立即撤销旧完整状态，并在持续移动时提交最新视口", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return batch(1, "failed");
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已收束但不完整"));
  const canvas = host.querySelector("canvas")!;
  const pointer = (type: string, x: number) => {
    const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: 260, button: 0 });
    Object.defineProperty(event, "pointerId", { value: 1 });
    canvas.dispatchEvent(event);
  };
  pointer("pointerdown", 400);
  pointer("pointermove", 100);
  await nextTick();
  expect(host.textContent).toContain("正在建立当前视口");
  for (const x of [-100, -300, -500, -700, -900]) {
    pointer("pointermove", x);
    await new Promise(resolve => setTimeout(resolve, 40));
  }
  expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport").length).toBeGreaterThanOrEqual(2);
});

it("当前可见目标缩为旧任务子集时不重载，也不等待或统计屏幕外瓦片", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 30, height: 1, right: 30, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  let starts = 0, polls = 0;
  let finishOffscreen!: (batch: ArrayBuffer) => void;
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") {
      starts += 1;
      return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 2, targets: [
        { tileX: 0, tileZ: -1, blocksPerPixel: 2 },
        { tileX: -1, tileZ: -1, blocksPerPixel: 2 },
      ] };
    }
    if (command === "next_map_viewport_batch") {
      polls += 1;
      return polls === 1 ? readyBatch({ state: "items" }) : new Promise<ArrayBuffer>(resolve => { finishOffscreen = resolve; });
    }
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host);
  app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(polls).toBe(2));
  expect(vi.mocked(invoke).mock.calls.find(call => call[0] === "start_map_viewport")?.[1]).toMatchObject({ centerX: 24, centerZ: -16, viewportWidth: 30, viewportHeight: 1 });
  await vi.waitFor(() => expect(host.querySelector('[role="status"]')?.textContent).toContain("1/2 个瓦片进入终态"));
  await vi.waitFor(() => expect(finishOffscreen).toBeDefined());

  host.querySelector("canvas")!.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight" }));
  await nextTick();
  expect(host.querySelector('[role="status"]')?.textContent).toBe("当前视口已完整加载");
  await new Promise(resolve => setTimeout(resolve, 120));
  expect(starts).toBe(1);

  const drawsBefore = context.strokeRect.mock.calls.length;
  finishOffscreen(batch(2, "failed", -1));
  await vi.waitFor(() => expect(context.strokeRect.mock.calls.length).toBeGreaterThan(drawsBefore));
  expect(host.querySelector('[role="status"]')?.textContent).toBe("当前视口已完整加载");
});

it.each(["start", "batch"] as const)("%s 请求失败时显示可重试终态，并在重试后收束", async failureAt => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  let starts = 0;
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") {
      starts += 1;
      if (failureAt === "start" && starts === 1) throw new Error("INVALID_RESPONSE");
      return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    }
    if (command === "next_map_viewport_batch") {
      if (failureAt === "batch" && starts === 1) throw new Error("INVALID_RESPONSE");
      return batch(1, "failed");
    }
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口请求失败，可重试"));
  expect(host.querySelector('[role="alert"]')?.textContent).toContain("INVALID_RESPONSE");
  if (failureAt === "batch") expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 });
  else expect(vi.mocked(invoke).mock.calls.some(call => call[0] === "cancel_map_viewport")).toBe(false);
  const retry = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "重试当前视口");
  expect(retry).toBeDefined();
  retry?.click();
  await vi.waitFor(() => expect(starts).toBe(2));
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已收束但不完整"));
  expect(host.textContent).not.toContain("当前视口请求失败，可重试");
});

it("新视口启动失败时取消仍在轮询的旧视口，迟到结果不污染手动重试", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  let starts = 0;
  let resolveOld!: (value: ArrayBuffer) => void;
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    if (command === "start_map_viewport") {
      starts += 1;
      if (starts === 2) throw { code: "RESOURCE_LIMIT" };
      const tileX = starts === 1 ? 0 : 2, tileZ = starts === 1 ? -1 : 0;
      return { sessionId: 7, viewportTaskId: starts === 1 ? 9 : 10, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX, tileZ, blocksPerPixel: 2 }] };
    }
    if (command === "next_map_viewport_batch") return (args as { viewportTaskId: number }).viewportTaskId === 9
      ? new Promise<ArrayBuffer>(resolve => { resolveOld = resolve; })
      : readyBatch({ viewportTaskId: 10, tileX: 2, tileZ: 0 });
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(resolveOld).toBeDefined());
  const xInput = host.querySelector("input")!;
  xInput.value = "1040"; xInput.dispatchEvent(new Event("input", { bubbles: true }));
  const zInput = host.querySelectorAll("input")[1]!;
  zInput.value = "16"; zInput.dispatchEvent(new Event("input", { bubbles: true }));
  await nextTick();
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "定位")!.click();
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口请求失败，可重试"));
  expect(starts).toBe(2);
  expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 });
  expect(invoke).not.toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 10 });
  resolveOld(readyBatch());
  await nextTick();
  expect(context.putImageData).not.toHaveBeenCalled();
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "重试当前视口")!.click();
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
  expect(starts).toBe(3);
});

it("组件卸载时取消当前视口，迟到批次不能继续提交", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  let resolveBatch!: (value: ArrayBuffer) => void;
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return new Promise<ArrayBuffer>(resolve => { resolveBatch = resolve; });
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(resolveBatch).toBeDefined());
  const before = vi.mocked(context.putImageData).mock.calls.length;
  app.unmount(); app = undefined;
  expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 });
  resolveBatch(batch(1, "partial"));
  await Promise.resolve();
  expect(vi.mocked(context.putImageData).mock.calls.length).toBe(before);
});

it("卸载前未返回的启动响应到达后仍取消该视口", async () => {
  let resolveStart!: (value: unknown) => void;
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return new Promise(resolve => { resolveStart = resolve; });
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(resolveStart).toBeDefined());
  app.unmount(); app = undefined;
  resolveStart({ sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] });
  await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 }));
  expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "next_map_viewport_batch")).toHaveLength(0);
});

it.each([
  ["完整", {}, "当前视口已完整加载", [0x12, 0x34, 0x56]],
  ["全缺失", { successfulChunks: 0, missingChunks: 1024 }, "当前视口为空范围，已完整加载", [0x20, 0x26, 0x2b]],
  ["不支持", { successfulChunks: 1023, unsupportedChunks: 1 }, "1 个区块不支持", [0x78, 0x5f, 0x24]],
  ["损坏", { successfulChunks: 1023, corruptChunks: 1 }, "1 个区块损坏", [0x7f, 0x26, 0x33]],
] as const)("ready 批次在组件内显示%s状态与像素", async (_label, options, expected, lastColor) => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return readyBatch(options);
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.querySelector('[role="status"]')?.textContent).toContain(expected));
  const putCalls = vi.mocked(context.putImageData).mock.calls;
  const image = putCalls[putCalls.length - 1]?.[0];
  expect(Array.from(image?.data.slice(-4, -1) ?? [])).toEqual(lastColor);
  if (options && ("unsupportedChunks" in options || "corruptChunks" in options)) expect(host.textContent).toContain("已收束但不完整");
  else expect(host.textContent).not.toContain("已收束但不完整");
});

it.each([
  ["会话", { sessionId: 8 }],
  ["视口任务", { viewportTaskId: 10 }],
  ["瓦片", { tileX: 1 }],
] as const)("%s身份不匹配的 ready 结果不能改变画布或完整度", async (_label, options) => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return readyBatch(options);
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.querySelector('[role="alert"]')?.textContent).toContain("INVALID_RESPONSE"));
  expect(context.putImageData).not.toHaveBeenCalled();
  expect(host.textContent).not.toContain("当前视口已完整加载");
});

it("切换世界后拒绝旧会话迟到的 ready 批次", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  const activeWorld = ref(world);
  let resolveOld!: (value: ArrayBuffer) => void;
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    const sessionId = (args as { sessionId?: number } | undefined)?.sessionId;
    if (command === "start_map_viewport") return { sessionId, viewportTaskId: sessionId === 7 ? 9 : 10, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return sessionId === 7 ? new Promise<ArrayBuffer>(resolve => { resolveOld = resolve; }) : new Promise(() => undefined);
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world: activeWorld.value }) })); app.mount(host);
  await vi.waitFor(() => expect(resolveOld).toBeDefined());
  activeWorld.value = { ...world, sessionId: 8 };
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")).toHaveLength(2));
  resolveOld(readyBatch());
  await nextTick();
  expect(context.putImageData).not.toHaveBeenCalled();
  expect(host.textContent).not.toContain("当前视口已完整加载");
});

it("跨数据倍率后新瓦片未就绪时继续绘制旧倍率图像", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => globalThis.setTimeout(() => callback(performance.now() + 200), 0));
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    const zoom = (args as { blocksPerPixel?: number } | undefined)?.blocksPerPixel;
    if (command === "start_map_viewport") return { sessionId: 7, viewportTaskId: zoom === 2 ? 9 : 10, blocksPerPixel: zoom, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: zoom }] };
    if (command === "next_map_viewport_batch") return (args as { viewportTaskId?: number }).viewportTaskId === 9 ? readyBatch() : new Promise(() => undefined);
    if (command === "cancel_map_viewport") return null;
    throw new Error("unexpected command");
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
  const oldDrawCalls = vi.mocked(context.drawImage).mock.calls;
  const oldImage = oldDrawCalls[oldDrawCalls.length - 1]?.[0];
  expect(oldImage).toBeDefined();
  vi.mocked(context.drawImage).mockClear();
  const zoomOut = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "－");
  expect(zoomOut).toBeDefined();
  zoomOut!.click();
  await vi.waitFor(() => expect(host.textContent).toContain("数据：4 方块/像素"));
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")).toHaveLength(2));
  expect(vi.mocked(context.drawImage).mock.calls.some(call => call[0] === oldImage)).toBe(true);
  expect(host.textContent).not.toContain("当前视口已完整加载");
});

it("返回已完成瓦片时局部重算不能把旧图像降级为逐块覆盖", async () => {
  rectSpy.mockReturnValue({ left: 0, top: 0, width: 1, height: 1, right: 1, bottom: 1, x: 0, y: 0, toJSON: () => ({}) });
  let starts = 0, returnPolls = 0;
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    if (command === "start_map_viewport") {
      starts += 1;
      const centerX = (args as { centerX: number }).centerX;
      const tileX = centerX >= 512 ? 1 : 0;
      return { sessionId: 7, viewportTaskId: starts + 8, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX, tileZ: -1, blocksPerPixel: 2 }] };
    }
    if (command === "next_map_viewport_batch") {
      const task = (args as { viewportTaskId: number }).viewportTaskId;
      if (task === 9) return readyBatch();
      if (task === 10) return readyBatch({ viewportTaskId: 10, tileX: 1 });
      return ++returnPolls === 1 ? batch(1, "partial", 0, 11) : new Promise(() => undefined);
    }
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host);
  app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
  const drawn = vi.mocked(context.drawImage).mock.calls;
  const oldImage = drawn[drawn.length - 1]?.[0];
  expect(oldImage).toBeDefined();
  const xInput = host.querySelector("input")!;
  const zInput = host.querySelectorAll("input")[1]!;
  const locate = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "定位")!;
  xInput.value = "600"; xInput.dispatchEvent(new Event("input", { bubbles: true }));
  zInput.value = "-16"; zInput.dispatchEvent(new Event("input", { bubbles: true })); await nextTick(); locate.click();
  await vi.waitFor(() => expect(starts).toBe(2));
  expect(vi.mocked(invoke).mock.calls.filter(call => call[0] === "start_map_viewport")[1]?.[1]).toMatchObject({ centerX: 600, centerZ: -16, viewportWidth: 1, viewportHeight: 1 });
  await vi.waitFor(() => expect(host.textContent).toContain("当前视口已完整加载"));
  const imageBuilds = vi.mocked(context.putImageData).mock.calls.length;
  xInput.value = "24"; xInput.dispatchEvent(new Event("input", { bubbles: true })); await nextTick(); locate.click();
  await vi.waitFor(() => expect(returnPolls).toBeGreaterThanOrEqual(2));
  expect(vi.mocked(context.putImageData).mock.calls).toHaveLength(imageBuilds);
  expect(vi.mocked(context.drawImage).mock.calls.some(call => call[0] === oldImage)).toBe(true);
  expect(host.querySelector('[role="status"]')?.textContent).not.toContain("已完整加载");
});

it("显示倍率低于瓦片倍率时仍接受与实际可见范围一致的目标", async () => {
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => globalThis.setTimeout(() => callback(performance.now() + 200), 0));
  let viewportTaskId = 8;
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    if (command === "start_map_viewport") {
      const request = args as { centerX: number; centerZ: number; viewportWidth: number; viewportHeight: number; blocksPerPixel: 1 | 2 | 4 | 8 | 16; displayBlocksPerPixel?: number };
      const targets = visibleTiles({ centerX: request.centerX, centerZ: request.centerZ, blocksPerPixel: request.displayBlocksPerPixel ?? request.blocksPerPixel }, request.viewportWidth, request.viewportHeight, request.blocksPerPixel);
      return { sessionId: 7, viewportTaskId: ++viewportTaskId, blocksPerPixel: request.blocksPerPixel, targetCount: targets.length, targets };
    }
    if (command === "next_map_viewport_batch") return new Promise(() => undefined);
    if (command === "cancel_map_viewport") return null;
    throw new Error(`unexpected command: ${command}`);
  });
  const host = document.createElement("div"); document.body.append(host); app = createApp(defineComponent({ setup: () => () => h(MapViewport, { world }) })); app.mount(host);
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.some(call => call[0] === "start_map_viewport")).toBe(true));
  const zoomIn = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "＋")!;
  zoomIn.click();
  await vi.waitFor(() => expect(host.textContent).toContain("显示与数据：1 方块/像素"));
  zoomIn.click();
  await vi.waitFor(() => expect(host.textContent).toContain("显示：2 像素/方块；数据：1 方块/像素"));
  const xInput = host.querySelector("input")!;
  xInput.value = "512"; xInput.dispatchEvent(new Event("input", { bubbles: true }));
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "定位")!.click();
  await vi.waitFor(() => expect(vi.mocked(invoke).mock.calls.some(([command, args]) => command === "start_map_viewport" && (args as { centerX?: number }).centerX === 512)).toBe(true));
  const request = vi.mocked(invoke).mock.calls.filter(([command, args]) => command === "start_map_viewport" && (args as { centerX?: number }).centerX === 512).pop()?.[1] as { blocksPerPixel: number; displayBlocksPerPixel: number };
  expect(request).toMatchObject({ blocksPerPixel: 1, displayBlocksPerPixel: 0.5 });
  expect(host.querySelector('[role="alert"]')).toBeNull();
  expect(host.querySelector('[role="status"]')?.textContent).not.toContain("请求失败");
});
