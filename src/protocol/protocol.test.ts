import { describe, expect, it } from "vitest";
import surfaceGoldenBase64 from "./fixtures/surface-v1.bin.gz.b64?raw";
import meshGoldenBase64 from "./fixtures/mesh-v1.bin.gz.b64?raw";
import mapBatchGoldenBase64 from "./fixtures/map-batch-v1.bin.gz.b64?raw";
import { decodeMapBatch } from "./map-batch-v1";
import { decodeMesh } from "./mesh-v1";
import { decodeSurface } from "./surface-v1";
import { MAX_METADATA_BYTES } from "./common";

async function inflateGolden(encoded: string): Promise<ArrayBuffer> {
  const compressed = Uint8Array.from(atob(encoded.trim()), (value) => value.charCodeAt(0));
  const stream = new Response(compressed.buffer).body!
    .pipeThrough(new DecompressionStream("gzip"));
  return new Response(stream).arrayBuffer();
}

function clone(buffer: ArrayBuffer): ArrayBuffer {
  return buffer.slice(0);
}

function mutate(buffer: ArrayBuffer, change: (view: DataView, bytes: Uint8Array) => void): ArrayBuffer {
  const copy = clone(buffer);
  change(new DataView(copy), new Uint8Array(copy));
  return copy;
}

function replaceMetadata(
  buffer: ArrayBuffer,
  change: (metadata: Record<string, unknown>) => unknown,
): ArrayBuffer {
  const oldView = new DataView(buffer);
  const offset = oldView.getUint32(16, true);
  const metadata = JSON.parse(new TextDecoder().decode(new Uint8Array(buffer, offset))) as Record<string, unknown>;
  const encoded = new TextEncoder().encode(JSON.stringify(change(metadata)));
  const result = new ArrayBuffer(offset + encoded.length);
  new Uint8Array(result).set(new Uint8Array(buffer, 0, offset));
  new Uint8Array(result).set(encoded, offset);
  const view = new DataView(result);
  view.setUint32(12, result.byteLength, true);
  view.setUint32(20, encoded.length, true);
  return result;
}

function fnv1a64(buffer: ArrayBuffer): bigint {
  let hash = 0xcbf2_9ce4_8422_2325n;
  for (const byte of new Uint8Array(buffer)) {
    hash = ((hash ^ BigInt(byte)) * 0x0000_0100_0000_01b3n) & 0xffff_ffff_ffff_ffffn;
  }
  return hash;
}

const surfaceGoldenBytes = await inflateGolden(surfaceGoldenBase64);
const meshGoldenBytes = await inflateGolden(meshGoldenBase64);
const mapBatchGoldenBytes = await inflateGolden(mapBatchGoldenBase64);
const surfaceGolden = () => clone(surfaceGoldenBytes);
const meshGolden = () => clone(meshGoldenBytes);
const mapBatchGolden = () => clone(mapBatchGoldenBytes);

describe("Rust 黄金向量", () => {
  it("解码 Rust surface v1 并匹配 Rust FNV-1a", () => {
    const buffer = surfaceGolden();
    const surface = decodeSurface(buffer);
    expect(fnv1a64(buffer)).toBe(13_390_334_150_084_859_276n);
    expect(surface.colors.length).toBe(20_736);
    expect(surface.colors[0]).toBe(0x12_3456);
    expect(surface.heights[0]).toBe(-64);
    expect(surface.metadata).toMatchObject({
      sessionId: 11,
      taskId: 12,
      centerChunkX: 1,
      centerChunkZ: 7,
      centerX: 24,
      centerY: 70,
      centerZ: 120,
      centerSource: "playerRespawn",
      worldSpawn: { x: 32, y: 72, z: 128 },
      successfulChunks: 81,
      failedChunks: 0,
      warnings: [],
    });
  });

  it("解码 Rust mesh v1 并保留 bounds、批次和 UTF-8 警告", () => {
    const buffer = meshGolden();
    const mesh = decodeMesh(buffer);
    expect(fnv1a64(buffer)).toBe(9_468_971_920_670_735_303n);
    expect(Array.from(mesh.indices)).toEqual([0, 1, 2, 0, 2, 3]);
    expect(mesh.metadata.bounds?.maxY).toBe(65);
    expect(mesh.metadata.warnings[0].blockName).toBe("模组:\"方块\\样例");
    expect(mesh.metadata).toMatchObject({
      sessionId: 21,
      taskId: 22,
      blockCount: 1,
      faceCount: 1,
      vertexCount: 4,
      indexCount: 6,
      batchCount: 1,
    });
  });

  it("解码 Rust map viewport batch v1 并匹配 Rust FNV-1a", () => {
    const buffer = mapBatchGolden();
    const batch = decodeMapBatch(buffer);
    expect(fnv1a64(buffer)).toBe(15_154_743_704_573_164_615n);
    expect(batch.metadata).toEqual({
      sessionId: 7,
      viewportTaskId: 9,
      batchSequence: 1,
      itemCount: 2,
      terminalItemCount: 1,
      hasMore: false,
      state: "items",
    });
    expect(batch.items[0]).toMatchObject({
      kind: "partial",
      tileX: -1,
      tileZ: 2,
      revision: 0x1_0000_0003,
      pixelX: 8,
      pixelZ: 16,
      width: 2,
      height: 1,
    });
    expect(batch.items[1]).toEqual({
      kind: "failed",
      tileX: 1,
      tileZ: 2,
      blocksPerPixel: 1,
      failure: "readFailed",
    });
  });
});

describe("公共容器拒绝畸形输入", () => {
  const corruptions: Array<[string, (view: DataView, bytes: Uint8Array) => void]> = [
    ["magic", (_view, bytes) => { bytes[0] = 0; }],
    ["version", (view) => { view.setUint16(4, 2, true); }],
    ["kind", (view) => { view.setUint16(6, 2, true); }],
    ["header length", (view) => { view.setUint32(8, 28, true); }],
    ["total length", (view) => { view.setUint32(12, 32, true); }],
    ["metadata inside table", (view) => { view.setUint32(16, 32, true); }],
    ["metadata length", (view) => { view.setUint32(20, 1, true); }],
    ["table offset", (view) => { view.setUint32(24, 36, true); }],
    ["section count", (view) => { view.setUint32(28, 4, true); }],
    ["section order", (view) => { view.setUint16(32, 2, true); }],
    ["scalar", (view) => { view.setUint16(34, 5, true); }],
    ["section gap", (view) => { view.setUint32(36, 84, true); }],
    ["section byte length", (view) => { view.setUint32(40, 4, true); }],
    ["section element count", (view) => { view.setUint32(44, 20_735, true); }],
  ];

  it.each(corruptions)("拒绝 %s", (_name, corruption) => {
    expect(() => decodeSurface(mutate(surfaceGolden(), corruption))).toThrow("INVALID_PROTOCOL");
  });

  it("拒绝截断、尾随、非法 UTF-8 和非 object metadata", () => {
    const source = surfaceGolden();
    expect(() => decodeSurface(source.slice(0, -1))).toThrow("INVALID_PROTOCOL");
    const tailed = new Uint8Array(source.byteLength + 1);
    tailed.set(new Uint8Array(source));
    expect(() => decodeSurface(tailed.buffer)).toThrow("INVALID_PROTOCOL");
    const invalidUtf8 = mutate(source, (view, bytes) => { bytes[view.getUint32(16, true)] = 0xff; });
    expect(() => decodeSurface(invalidUtf8)).toThrow("INVALID_PROTOCOL");
    expect(() => decodeSurface(replaceMetadata(source, () => []))).toThrow("INVALID_PROTOCOL");
  });

  it("在解析 JSON 前拒绝超过独立上限的 metadata", () => {
    const oversized = replaceMetadata(surfaceGolden(), (metadata) => ({
      ...metadata,
      padding: "x".repeat(MAX_METADATA_BYTES),
    }));
    expect(() => decodeSurface(oversized)).toThrow("INVALID_PROTOCOL");
  });
});

describe("surface 语义校验", () => {
  it("拒绝高位颜色、取消状态、区块内混合状态和失败列非零高度", () => {
    const source = surfaceGolden();
    const view = new DataView(source);
    const colorOffset = view.getUint32(36, true);
    const heightOffset = view.getUint32(52, true);
    const statusOffset = view.getUint32(68, true);
    for (const malformed of [
      mutate(source, (next) => { next.setUint32(colorOffset, 0xff00_0000, true); }),
      mutate(source, (_next, bytes) => { bytes[statusOffset] = 4; }),
      mutate(source, (_next, bytes) => { bytes[statusOffset + 1] = 1; }),
      mutate(source, (next, bytes) => {
        bytes.fill(1, statusOffset, statusOffset + 16);
        next.setInt32(heightOffset, 1, true);
      }),
    ]) {
      expect(() => decodeSurface(malformed)).toThrow("INVALID_PROTOCOL");
    }
  });

  it("拒绝不一致统计、警告和不可无损表示的 ID", () => {
    const source = surfaceGolden();
    for (const malformed of [
      replaceMetadata(source, (metadata) => ({ ...metadata, successfulChunks: 80 })),
      replaceMetadata(source, (metadata) => ({ ...metadata, warnings: [{ kind: "chunkStatus", status: "missing", chunks: 1 }] })),
      replaceMetadata(source, (metadata) => ({ ...metadata, sessionId: Number.MAX_SAFE_INTEGER + 1 })),
      replaceMetadata(source, (metadata) => ({ ...metadata, centerSource: "worldSpawn", worldSpawn: null })),
      replaceMetadata(source, (metadata) => ({ ...metadata, centerSource: "originFallback" })),
    ]) {
      expect(() => decodeSurface(malformed)).toThrow("INVALID_PROTOCOL");
    }
  });
});

describe("mesh 语义校验", () => {
  it("拒绝非有限位置、非轴向法线和越界索引", () => {
    const source = meshGolden();
    const view = new DataView(source);
    const positionOffset = view.getUint32(36, true);
    const normalOffset = view.getUint32(52, true);
    const indexOffset = view.getUint32(68, true);
    for (const malformed of [
      mutate(source, (next) => { next.setFloat32(positionOffset, Number.NaN, true); }),
      mutate(source, (next) => { next.setFloat32(normalOffset, 0.5, true); }),
      mutate(source, (next) => { next.setFloat32(normalOffset + 8, 0, true); }),
      mutate(source, (next) => { next.setUint32(indexOffset, 4, true); }),
    ]) {
      expect(() => decodeMesh(malformed)).toThrow("INVALID_PROTOCOL");
    }
  });

  it("拒绝非法批次颜色、范围、统计和保留字段", () => {
    const source = meshGolden();
    const batchOffset = new DataView(source).getUint32(84, true);
    for (const malformed of [
      mutate(source, (view) => { view.setUint32(batchOffset + 4, 0xff00_0000, true); }),
      mutate(source, (view) => { view.setUint32(batchOffset + 12, 1, true); }),
      mutate(source, (view) => { view.setUint32(batchOffset + 24, 2, true); }),
      mutate(source, (view) => { view.setUint32(batchOffset + 28, 1, true); }),
    ]) {
      expect(() => decodeMesh(malformed)).toThrow("INVALID_PROTOCOL");
    }
  });

  it("拒绝不一致 metadata、bounds、warning 和不可无损表示的 ID", () => {
    const source = meshGolden();
    for (const malformed of [
      replaceMetadata(source, (metadata) => ({ ...metadata, vertexCount: 5 })),
      replaceMetadata(source, (metadata) => ({
        ...metadata,
        bounds: { ...(metadata.bounds as Record<string, unknown>), maxX: 0 },
      })),
      replaceMetadata(source, (metadata) => ({
        ...metadata,
        warnings: [{ ...((metadata.warnings as Record<string, unknown>[])[0]), occurrences: 0 }],
      })),
      replaceMetadata(source, (metadata) => ({ ...metadata, taskId: Number.MAX_SAFE_INTEGER + 1 })),
    ]) {
      expect(() => decodeMesh(malformed)).toThrow("INVALID_PROTOCOL");
    }
  });
});
