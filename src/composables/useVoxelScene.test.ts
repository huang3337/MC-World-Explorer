import { describe, expect, it, vi } from "vitest";
import * as THREE from "three";
import type { MeshData } from "../protocol/mesh-v1";
import { calculateCameraFit, createSceneBundle, disposeSceneBundle } from "./useVoxelScene";

function floatBits(value: number): number {
  const bytes = new ArrayBuffer(4);
  const view = new DataView(bytes);
  view.setFloat32(0, value, true);
  return view.getUint32(0, true);
}

function meshData(): MeshData {
  const positions = new Float32Array([
    0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
    0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1,
  ]);
  const normals = new Float32Array(positions.length);
  for (let index = 2; index < normals.length; index += 3) normals[index] = 1;
  const indices = new Uint32Array([0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7]);
  return {
    positions,
    normals,
    indices,
    batches: new Uint32Array([
      0, 0x123456, floatBits(1), 0, 6, 1, 1, 0,
      2, 0xabcdef, floatBits(0.5), 6, 6, 1, 1, 0,
    ]),
    metadata: {
      sessionId: 1,
      taskId: 2,
      chunkRect: { minX: -1, minZ: 2, width: 1, depth: 1 },
      originWorldX: -16,
      originWorldZ: 32,
      bounds: { minX: 0, minY: 0, minZ: 0, maxX: 1, maxY: 1, maxZ: 1 },
      blockCount: 2,
      faceCount: 2,
      vertexCount: 8,
      indexCount: 12,
      batchCount: 2,
      warnings: [],
    },
  };
}

describe("Three.js 批次映射", () => {
  it("以单 geometry 共享协议 TypedArray，并按批次建立 group 和材质", () => {
    const data = meshData();
    const bundle = createSceneBundle(data);
    expect(bundle.geometry.getAttribute("position").array).toBe(data.positions);
    expect(bundle.geometry.getAttribute("normal").array).toBe(data.normals);
    expect(bundle.geometry.getIndex()?.array).toBe(data.indices);
    expect(bundle.geometry.groups).toEqual([
      { start: 0, count: 6, materialIndex: 0 },
      { start: 6, count: 6, materialIndex: 1 },
    ]);
    expect(bundle.materials).toHaveLength(2);
    expect(bundle.materials[0].color.getHex()).toBe(0x123456);
    expect(bundle.materials[0].transparent).toBe(false);
    expect(bundle.materials[0].depthWrite).toBe(true);
    expect(bundle.materials[1].color.getHex()).toBe(0xabcdef);
    expect(bundle.materials[1].opacity).toBeCloseTo(0.5);
    expect(bundle.materials[1].transparent).toBe(true);
    expect(bundle.materials[1].depthWrite).toBe(false);
    expect(bundle.materials.every((material) => material.side === THREE.FrontSide)).toBe(true);
    disposeSceneBundle(bundle);
  });

  it("释放单 geometry 和全部有限材质", () => {
    const bundle = createSceneBundle(meshData());
    const geometryDispose = vi.spyOn(bundle.geometry, "dispose");
    const materialDisposes = bundle.materials.map((material) => vi.spyOn(material, "dispose"));
    disposeSceneBundle(bundle);
    expect(geometryDispose).toHaveBeenCalledOnce();
    for (const dispose of materialDisposes) expect(dispose).toHaveBeenCalledOnce();
    expect(bundle.scene.children).not.toContain(bundle.mesh);
  });

  it("接受无顶点、无批次和 null bounds 的合法空场景", () => {
    const data = meshData();
    const empty: MeshData = {
      ...data,
      positions: new Float32Array(),
      normals: new Float32Array(),
      indices: new Uint32Array(),
      batches: new Uint32Array(),
      metadata: {
        ...data.metadata,
        bounds: null,
        blockCount: 0,
        faceCount: 0,
        vertexCount: 0,
        indexCount: 0,
        batchCount: 0,
      },
    };
    const bundle = createSceneBundle(empty);
    expect(bundle.geometry.getAttribute("position").count).toBe(0);
    expect(bundle.geometry.groups).toHaveLength(0);
    expect(bundle.materials).toHaveLength(0);
    disposeSceneBundle(bundle);
  });
});

describe("相机自动 fit", () => {
  it("以 bounds 中心为固定目标，并让窄视口使用更远距离", () => {
    const bounds = { minX: -8, minY: -64, minZ: 2, maxX: 24, maxY: 320, maxZ: 34 };
    const wide = calculateCameraFit(bounds, 16 / 9);
    const narrow = calculateCameraFit(bounds, 0.5);
    expect(wide.target).toEqual({ x: 8, y: 128, z: 18 });
    expect(wide.radius).toBeGreaterThan(190);
    expect(narrow.distance).toBeGreaterThan(wide.distance);
    expect(wide.near).toBeGreaterThan(0);
    expect(wide.far).toBeGreaterThan(wide.distance);
  });

  it("空网格和非法 aspect 使用有限默认视图", () => {
    expect(calculateCameraFit(null, Number.NaN)).toEqual({
      target: { x: 0, y: 0, z: 0 }, radius: 1, distance: 4, near: 0.1, far: 16,
    });
    const fit = calculateCameraFit({ minX: 0, minY: 0, minZ: 0, maxX: 0, maxY: 0, maxZ: 0 }, 0);
    expect(Object.values(fit.target).every(Number.isFinite)).toBe(true);
    expect(fit.distance).toBeGreaterThan(0);
  });
});
