import { expect, it } from "vitest";
import { decodeMapBatch } from "./map-batch-v1";

const DESCRIPTOR_WORDS = 20;

function batchBuffer(): ArrayBuffer {
  const metadataBytes = new TextEncoder().encode(JSON.stringify({
    sessionId: 7,
    viewportTaskId: 9,
    batchSequence: 1,
    itemCount: 2,
    terminalItemCount: 1,
    hasMore: false,
    state: "items",
  }));
  const tableEnd = 32 + 4 * 16;
  const descriptorsOffset = tableEnd;
  const descriptorsLength = 2 * DESCRIPTOR_WORDS * 4;
  const colorsOffset = descriptorsOffset + descriptorsLength;
  const heightsOffset = colorsOffset + 8;
  const statusesOffset = heightsOffset + 8;
  const metadataOffset = (statusesOffset + 2 + 3) & ~3;
  const buffer = new ArrayBuffer(metadataOffset + metadataBytes.length);
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  bytes.set([77, 67, 87, 69]);
  view.setUint16(4, 1, true);
  view.setUint16(6, 4, true);
  view.setUint32(8, 32, true);
  view.setUint32(12, buffer.byteLength, true);
  view.setUint32(16, metadataOffset, true);
  view.setUint32(20, metadataBytes.length, true);
  view.setUint32(24, 32, true);
  view.setUint32(28, 4, true);
  const sections = [
    [20, 4, descriptorsOffset, descriptorsLength, 2 * DESCRIPTOR_WORDS],
    [21, 4, colorsOffset, 8, 2],
    [22, 5, heightsOffset, 8, 2],
    [23, 1, statusesOffset, 2, 2],
  ];
  sections.forEach(([id, scalar, offset, length, count], index) => {
    const at = 32 + index * 16;
    view.setUint16(at, id, true);
    view.setUint16(at + 2, scalar, true);
    view.setUint32(at + 4, offset, true);
    view.setUint32(at + 8, length, true);
    view.setUint32(at + 12, count, true);
  });
  const word = (item: number, index: number, value: number) => {
    view.setUint32(descriptorsOffset + (item * DESCRIPTOR_WORDS + index) * 4, value, true);
  };
  word(0, 0, 1); word(0, 1, 0xffff_ffff); word(0, 2, 2); word(0, 3, 1);
  word(0, 4, 3); word(0, 5, 1); word(0, 6, 8); word(0, 7, 16);
  word(0, 8, 2); word(0, 9, 1); word(0, 10, 1); word(0, 11, 256);
  word(0, 16, 0); word(0, 17, 2);
  word(1, 0, 3); word(1, 1, 0); word(1, 2, 2); word(1, 3, 1);
  word(1, 16, 2); word(1, 18, 1);
  view.setUint32(colorsOffset, 0x0012_3456, true);
  view.setInt32(heightsOffset, 64, true);
  bytes[statusesOffset + 1] = 4;
  bytes.set(metadataBytes, metadataOffset);
  return buffer;
}

function readyBatchBuffer(): ArrayBuffer {
  const cells = 256 * 256;
  const metadataBytes = new TextEncoder().encode(JSON.stringify({
    sessionId: 7, viewportTaskId: 9, batchSequence: 2, itemCount: 1,
    terminalItemCount: 1, hasMore: false, state: "ended",
  }));
  const descriptorsOffset = 96;
  const colorsOffset = descriptorsOffset + DESCRIPTOR_WORDS * 4;
  const heightsOffset = colorsOffset + cells * 4;
  const statusesOffset = heightsOffset + cells * 4;
  const metadataOffset = statusesOffset + cells;
  const buffer = new ArrayBuffer(metadataOffset + metadataBytes.length);
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  bytes.set([77, 67, 87, 69]);
  view.setUint16(4, 1, true); view.setUint16(6, 4, true);
  view.setUint32(8, 32, true); view.setUint32(12, buffer.byteLength, true);
  view.setUint32(16, metadataOffset, true); view.setUint32(20, metadataBytes.length, true);
  view.setUint32(24, 32, true); view.setUint32(28, 4, true);
  const sections = [
    [20, 4, descriptorsOffset, DESCRIPTOR_WORDS * 4, DESCRIPTOR_WORDS],
    [21, 4, colorsOffset, cells * 4, cells],
    [22, 5, heightsOffset, cells * 4, cells],
    [23, 1, statusesOffset, cells, cells],
  ];
  sections.forEach(([id, scalar, offset, length, count], index) => {
    const at = 32 + index * 16;
    view.setUint16(at, id, true); view.setUint16(at + 2, scalar, true);
    view.setUint32(at + 4, offset, true); view.setUint32(at + 8, length, true);
    view.setUint32(at + 12, count, true);
  });
  const word = (index: number, value: number) => {
    view.setUint32(descriptorsOffset + index * 4, value, true);
  };
  word(0, 2); word(2, 2); word(3, 1); word(8, 256); word(9, 256);
  word(10, 256); word(11, 256); word(13, 256); word(17, cells);
  bytes.fill(1, statusesOffset, statusesOffset + cells);
  bytes.set(metadataBytes, metadataOffset);
  return buffer;
}

it("严格解码局部结果、64 位 revision 与稳定失败", () => {
  const batch = decodeMapBatch(batchBuffer());
  expect(batch.metadata).toMatchObject({ sessionId: 7, viewportTaskId: 9, itemCount: 2 });
  expect(batch.items[0]).toMatchObject({
    kind: "partial", tileX: -1, tileZ: 2, revision: 0x1_0000_0003,
    pixelX: 8, pixelZ: 16, width: 2, height: 1,
  });
  expect(batch.items[1]).toEqual({
    kind: "failed", tileX: 0, tileZ: 2, blocksPerPixel: 1, failure: "readFailed",
  });
});

it("拒绝不连续载荷、非法状态和终态计数不一致", () => {
  const offset = 96;
  const badOffset = batchBuffer();
  new DataView(badOffset).setUint32(offset + 16 * 4, 1, true);
  expect(() => decodeMapBatch(badOffset)).toThrow("INVALID_PROTOCOL");

  const badStatus = batchBuffer();
  const statusOffset = new DataView(badStatus).getUint32(84, true);
  new Uint8Array(badStatus)[statusOffset] = 5;
  expect(() => decodeMapBatch(badStatus)).toThrow("INVALID_PROTOCOL");

  const badTerminal = batchBuffer();
  const metadataOffset = new DataView(badTerminal).getUint32(16, true);
  const metadata = JSON.parse(new TextDecoder().decode(new Uint8Array(badTerminal, metadataOffset)));
  metadata.terminalItemCount = 0;
  const encoded = new TextEncoder().encode(JSON.stringify(metadata));
  const resized = badTerminal.slice(0, metadataOffset + encoded.length);
  new Uint8Array(resized).set(encoded, metadataOffset);
  const view = new DataView(resized);
  view.setUint32(12, resized.byteLength, true);
  view.setUint32(20, encoded.length, true);
  expect(() => decodeMapBatch(resized)).toThrow("INVALID_PROTOCOL");
});

it("严格解码完整 ready 瓦片及其状态统计", () => {
  const batch = decodeMapBatch(readyBatchBuffer());
  expect(batch.metadata).toMatchObject({ state: "ended", terminalItemCount: 1 });
  expect(batch.items[0]).toMatchObject({
    kind: "ready", tileX: 0, tileZ: 2, blocksPerPixel: 1,
    successfulChunks: 0, missingChunks: 256, unsupportedChunks: 0, corruptChunks: 0,
  });
  expect(batch.items[0].kind === "ready" && batch.items[0].statuses[65_535]).toBe(1);
});
