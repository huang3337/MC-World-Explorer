import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, ref, type App as VueApp, type Ref } from "vue";
import type { MeshData } from "../protocol/mesh-v1";

const rendererState = vi.hoisted(() => ({
  instances: [] as Array<{
    domElement: HTMLCanvasElement;
    setPixelRatio: ReturnType<typeof vi.fn>;
    setSize: ReturnType<typeof vi.fn>;
    render: ReturnType<typeof vi.fn>;
    resetState: ReturnType<typeof vi.fn>;
    setAnimationLoop: ReturnType<typeof vi.fn>;
    dispose: ReturnType<typeof vi.fn>;
    forceContextLoss: ReturnType<typeof vi.fn>;
    outputColorSpace: string;
  }>,
  failNextRender: false,
  failNextSetSize: false,
}));

vi.mock("three", async (importOriginal) => {
  const actual = await importOriginal<typeof import("three")>();
  class WebGLRenderer {
    domElement = document.createElement("canvas");
    setPixelRatio = vi.fn();
    setSize = vi.fn(() => {
      if (rendererState.failNextSetSize) {
        rendererState.failNextSetSize = false;
        throw new Error("synthetic resize failure");
      }
    });
    render = vi.fn(() => {
      if (rendererState.failNextRender) {
        rendererState.failNextRender = false;
        throw new Error("synthetic render failure");
      }
    });
    resetState = vi.fn();
    setAnimationLoop = vi.fn();
    dispose = vi.fn();
    forceContextLoss = vi.fn();
    outputColorSpace = "";
    constructor() { rendererState.instances.push(this); }
  }
  return { ...actual, WebGLRenderer };
});

import * as THREE from "three";
import VoxelViewer from "./VoxelViewer.vue";

let app: VueApp | undefined;
let data: Ref<MeshData>;
let frameId = 0;
let frames: Map<number, FrameRequestCallback>;
let rectSpy: { mockRestore(): void };
let rectWidth: number;

function floatBits(value: number): number {
  const bytes = new ArrayBuffer(4);
  const view = new DataView(bytes);
  view.setFloat32(0, value, true);
  return view.getUint32(0, true);
}

function meshData(offset = 0): MeshData {
  return {
    positions: new Float32Array([offset, 0, 0, offset + 1, 0, 0, offset + 1, 1, 0, offset, 1, 0]),
    normals: new Float32Array([0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1]),
    indices: new Uint32Array([0, 1, 2, 0, 2, 3]),
    batches: new Uint32Array([2, 0xabcdef, floatBits(0.5), 0, 6, 1, 1, 0]),
    metadata: {
      sessionId: 1,
      taskId: 2 + offset,
      chunkRect: { minX: 0, minZ: 0, width: 1, depth: 1 },
      originWorldX: 0,
      originWorldZ: 0,
      bounds: { minX: offset, minY: 0, minZ: 0, maxX: offset + 1, maxY: 1, maxZ: 0 },
      blockCount: 1,
      faceCount: 1,
      vertexCount: 4,
      indexCount: 6,
      batchCount: 1,
      warnings: offset === 0 ? [] : [{
        kind: "neighborUnavailable", chunkX: offset, chunkZ: 0,
        failure: "missing", blockName: null, occurrences: 1,
      }],
    },
  };
}

function flushFrame(): void {
  const pending = [...frames.entries()];
  frames.clear();
  for (const [id, callback] of pending) callback(id);
}

function pointer(type: string, x: number, y: number, id = 9, button = 0): Event {
  const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button });
  Object.defineProperty(event, "pointerId", { value: id });
  return event;
}

function mount(initial = meshData(), disabled = false): HTMLElement {
  data = ref(initial);
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(defineComponent({ setup: () => () => h(VoxelViewer, { data: data.value, disabled }) }));
  app.mount(host);
  return host;
}

beforeEach(() => {
  rendererState.instances.length = 0;
  rendererState.failNextRender = false;
  rendererState.failNextSetSize = false;
  frameId = 0;
  frames = new Map();
  rectWidth = 560;
  vi.stubGlobal("requestAnimationFrame", vi.fn((callback: FrameRequestCallback) => {
    frameId += 1;
    frames.set(frameId, callback);
    return frameId;
  }));
  vi.stubGlobal("cancelAnimationFrame", vi.fn((id: number) => { frames.delete(id); }));
  vi.stubGlobal("ResizeObserver", class {
    observe = vi.fn();
    disconnect = vi.fn();
    constructor(_callback: ResizeObserverCallback) {}
  });
  rectSpy = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect")
    .mockImplementation(() => ({ left: 0, top: 0, width: rectWidth, height: 360, right: rectWidth, bottom: 360, x: 0, y: 0, toJSON: () => ({}) }));
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  rectSpy.mockRestore();
  vi.unstubAllGlobals();
});

it("挂载有限批次场景并在首帧后进入就绪，不建立持续动画", async () => {
  vi.stubGlobal("devicePixelRatio", 3);
  const host = mount();
  await nextTick();
  expect(rendererState.instances).toHaveLength(1);
  const renderer = rendererState.instances[0];
  expect(renderer.setPixelRatio).toHaveBeenCalledWith(2);
  expect(renderer.setSize).toHaveBeenCalledWith(560, 360, false);
  expect(frames.size).toBe(1);
  flushFrame();
  await nextTick();
  expect(renderer.render).toHaveBeenCalledOnce();
  expect(frames.size).toBe(0);
  expect(host.textContent).toContain("1 方块，1 面，4 顶点，1 批次");
  expect(host.textContent).toContain("简化三维已就绪");
  expect(host.querySelector(".voxel-host canvas")).not.toBeNull();
});

it("初始化中途失败会立即释放 renderer，不等待组件卸载", async () => {
  rendererState.failNextSetSize = true;
  const host = mount();
  await nextTick();
  const renderer = rendererState.instances[0];
  expect(host.textContent).toContain("无法建立新的简化三维场景");
  expect(renderer.dispose).toHaveBeenCalledOnce();
  expect(renderer.forceContextLoss).toHaveBeenCalledOnce();
  expect(host.querySelector(".voxel-host canvas")).toBeNull();
  expect(frames.size).toBe(0);
});

it("运行期 resize 失败保留可重试尺寸，并在下一次事件恢复", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const renderer = rendererState.instances[0];
  rendererState.failNextSetSize = true;
  rectWidth = 600;
  globalThis.dispatchEvent(new Event("resize"));
  await nextTick();
  expect(host.textContent).toContain("无法建立新的简化三维场景");
  expect(renderer.setSize).toHaveBeenLastCalledWith(600, 360, false);

  globalThis.dispatchEvent(new Event("resize"));
  expect(renderer.setSize).toHaveBeenCalledWith(600, 360, false);
  expect(renderer.setSize).toHaveBeenCalledTimes(3);
  expect(frames.size).toBe(1);
  flushFrame();
  await nextTick();
  expect(host.textContent).toContain("简化三维已就绪");
});

it("警告详情最多展开八项，并明确剩余聚合数量", async () => {
  const initial = meshData();
  initial.metadata.warnings = Array.from({ length: 10 }, (_, index) => ({
    kind: "fallbackBlock" as const,
    chunkX: index,
    chunkZ: 0,
    failure: null,
    blockName: `mod:block_${index}`,
    occurrences: index + 1,
  }));
  const host = mount(initial);
  await nextTick();
  flushFrame();
  await nextTick();
  expect(host.querySelectorAll(".voxel-warnings li")).toHaveLength(9);
  expect(host.textContent).toContain("未知方块 mod:block_7");
  expect(host.textContent).not.toContain("未知方块 mod:block_8");
  expect(host.textContent).toContain("另有 2 项聚合信息未展开");
});

it("新场景首帧成功后才释放旧场景，渲染失败时保留旧完整视图", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const disposeGeometry = vi.spyOn(THREE.BufferGeometry.prototype, "dispose");

  data.value = meshData(10);
  await nextTick();
  expect(disposeGeometry).not.toHaveBeenCalled();
  expect(host.textContent).toContain("没有读取失败或兼容提示");
  flushFrame();
  await nextTick();
  expect(disposeGeometry).toHaveBeenCalledTimes(1);
  expect(host.textContent).toContain("场景读取成功，另有 1 项降级或兼容信息");

  rendererState.failNextRender = true;
  data.value = meshData(20);
  await nextTick();
  flushFrame();
  await nextTick();
  expect(disposeGeometry).toHaveBeenCalledTimes(2);
  expect(host.textContent).toContain("已保留此前完整视图");
  expect(host.textContent).toContain("相邻区块缺失");
  expect(host.textContent).toContain("区块 X 10");
  expect(host.textContent).not.toContain("区块 X 20");
});

it("更新结果建场失败时丢弃更早的 pending，不让过期场景复活", async () => {
  mount();
  await nextTick();
  flushFrame();
  const renderer = rendererState.instances[0];
  data.value = meshData(10);
  await nextTick();
  expect(frames.size).toBe(1);
  const setAttribute = vi.spyOn(THREE.BufferGeometry.prototype, "setAttribute").mockImplementationOnce(() => {
    throw new Error("synthetic allocation failure");
  });
  data.value = meshData(20);
  await nextTick();
  expect(frames.size).toBe(0);
  expect(renderer.render).toHaveBeenCalledTimes(1);
  setAttribute.mockRestore();
});

it("拖动、滚轮和键盘只合并请求单帧，空闲时不循环渲染", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const view = host.querySelector(".voxel-host") as HTMLDivElement;
  view.dispatchEvent(pointer("pointerdown", 100, 100));
  view.dispatchEvent(pointer("pointermove", 120, 110));
  view.dispatchEvent(pointer("pointermove", 130, 115));
  view.dispatchEvent(new WheelEvent("wheel", { bubbles: true, cancelable: true, deltaY: 50 }));
  view.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, key: "ArrowLeft" }));
  expect(frames.size).toBe(1);
  flushFrame();
  expect(frames.size).toBe(0);
  expect(rendererState.instances[0].render).toHaveBeenCalledTimes(2);
  (host.querySelector(".voxel-reset") as HTMLButtonElement).click();
  expect(frames.size).toBe(1);
});

it("左键四像素以内不旋转，越过阈值后旋转且失焦终止拖动", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const view = host.querySelector(".voxel-host")!;
  view.dispatchEvent(pointer("pointerdown", 100, 100));
  view.dispatchEvent(pointer("pointermove", 104, 100));
  expect(frames.size).toBe(0);
  view.dispatchEvent(pointer("pointerup", 104, 100));
  expect(frames.size).toBe(0);
  view.dispatchEvent(pointer("pointerdown", 100, 100));
  view.dispatchEvent(pointer("pointermove", 104, 101));
  expect(frames.size).toBe(1);
  flushFrame();
  view.dispatchEvent(new Event("blur"));
  view.dispatchEvent(pointer("pointermove", 120, 120));
  expect(frames.size).toBe(0);
});

it("右键按 Java 屏幕空间公式平移，保持角度，限制中心并用 R 完整恢复", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const renderer = rendererState.instances[0];
  const camera = (renderer.render.mock.calls[0] as unknown as [THREE.Scene, THREE.PerspectiveCamera])[1];
  const initialPosition = camera.position.clone();
  const initialRotation = camera.quaternion.clone();
  const center = new THREE.Vector3(0.5, 0.5, 0);
  const distance = initialPosition.distanceTo(center);
  const scale = 2 * distance * Math.tan(Math.PI / 8) / 360;
  const view = host.querySelector(".voxel-host")!;
  view.dispatchEvent(pointer("pointerdown", 100, 100, 9, 2));
  view.dispatchEvent(pointer("pointermove", 110, 100, 9, 2));
  flushFrame();
  expect(camera.position.x - initialPosition.x).toBeCloseTo(-Math.cos(Math.PI / 4) * 10 * scale);
  expect(camera.position.z - initialPosition.z).toBeCloseTo(Math.sin(Math.PI / 4) * 10 * scale);
  expect(camera.quaternion.angleTo(initialRotation)).toBeCloseTo(0);
  view.dispatchEvent(pointer("pointermove", 1e6, 1e6, 9, 2));
  flushFrame();
  expect(camera.position.distanceTo(initialPosition)).toBeCloseTo(Math.SQRT2);
  view.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, key: "R" }));
  flushFrame();
  expect(camera.position.distanceTo(initialPosition)).toBeCloseTo(0);
  expect(camera.quaternion.angleTo(initialRotation)).toBeCloseTo(0);
  view.dispatchEvent(pointer("pointermove", 120, 120, 9, 2));
  expect(frames.size).toBe(0);
});

it("Esc 只发出内嵌预览关闭事件，不关闭应用或触发相机绘制", async () => {
  const close = vi.fn();
  const container = document.createElement("div");
  app = createApp(VoxelViewer, { data: meshData(), onClose: close });
  app.mount(container);
  await nextTick();
  flushFrame();
  const view = container.querySelector(".voxel-host")!;
  const event = new KeyboardEvent("keydown", { bubbles: true, cancelable: true, key: "Escape" });
  view.dispatchEvent(event);
  expect(close).toHaveBeenCalledOnce();
  expect(event.defaultPrevented).toBe(true);
  expect(frames.size).toBe(0);
});

it("保留显示模式拒绝全部相机输入和重置操作", async () => {
  const host = mount(meshData(), true);
  await nextTick();
  flushFrame();
  const section = host.querySelector(".voxel-viewer") as HTMLElement;
  const view = host.querySelector(".voxel-host") as HTMLDivElement;
  const reset = host.querySelector(".voxel-reset") as HTMLButtonElement;
  expect(section.getAttribute("aria-disabled")).toBe("true");
  expect(view.tabIndex).toBe(-1);
  expect(reset.disabled).toBe(true);
  view.dispatchEvent(pointer("pointerdown", 100, 100));
  view.dispatchEvent(pointer("pointermove", 130, 115));
  view.dispatchEvent(new WheelEvent("wheel", { bubbles: true, cancelable: true, deltaY: 50 }));
  view.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, key: "ArrowLeft" }));
  reset.click();
  expect(frames.size).toBe(0);
  expect(host.textContent).toContain("当前三维仅保留显示");
});

it("WebGL context loss 暂停渲染，恢复后按需重绘", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const renderer = rendererState.instances[0];
  const lost = new Event("webglcontextlost", { cancelable: true });
  renderer.domElement.dispatchEvent(lost);
  await nextTick();
  expect(lost.defaultPrevented).toBe(true);
  expect(host.textContent).toContain("WebGL 上下文已丢失");
  expect(frames.size).toBe(0);
  renderer.domElement.dispatchEvent(new Event("webglcontextrestored"));
  expect(renderer.resetState).toHaveBeenCalledOnce();
  expect(frames.size).toBe(1);
  flushFrame();
  await nextTick();
  expect(host.textContent).toContain("简化三维已就绪");
});

it("卸载时取消 RAF 并释放 scene、material、renderer 和 WebGL 上下文", async () => {
  const host = mount();
  await nextTick();
  flushFrame();
  const renderer = rendererState.instances[0];
  const geometryDispose = vi.spyOn(THREE.BufferGeometry.prototype, "dispose");
  const materialDispose = vi.spyOn(THREE.Material.prototype, "dispose");
  (host.querySelector(".voxel-reset") as HTMLButtonElement).click();
  expect(frames.size).toBe(1);
  app!.unmount();
  app = undefined;
  expect(frames.size).toBe(0);
  expect(geometryDispose).toHaveBeenCalled();
  expect(materialDispose).toHaveBeenCalled();
  expect(renderer.setAnimationLoop).toHaveBeenCalledWith(null);
  expect(renderer.dispose).toHaveBeenCalledOnce();
  expect(renderer.forceContextLoss).toHaveBeenCalledOnce();
  expect(host.textContent).toBe("");
});
