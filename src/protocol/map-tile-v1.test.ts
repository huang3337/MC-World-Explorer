import { expect, it } from "vitest";
import { decodeMapTile } from "./map-tile-v1";

function tileBuffer(): ArrayBuffer {
  const cells = 256 * 256, tableEnd = 80;
  const colorOffset = tableEnd, heightOffset = colorOffset + cells * 4, statusOffset = heightOffset + cells * 4;
  const metadata = new TextEncoder().encode(JSON.stringify({ sessionId: 7, taskId: 9, tileX: -1, tileZ: 2, blocksPerPixel: 1, originBlockX: -256, originBlockZ: 512, successfulChunks: 0, missingChunks: 256, unsupportedChunks: 0, corruptChunks: 0 }));
  const metadataOffset = statusOffset + cells, buffer = new ArrayBuffer(metadataOffset + metadata.length), view = new DataView(buffer), bytes = new Uint8Array(buffer);
  bytes.set([77, 67, 87, 69]); view.setUint16(4, 1, true); view.setUint16(6, 3, true); view.setUint32(8, 32, true); view.setUint32(12, buffer.byteLength, true); view.setUint32(16, metadataOffset, true); view.setUint32(20, metadata.length, true); view.setUint32(24, 32, true); view.setUint32(28, 3, true);
  for (const [index, id, scalar, offset, length] of [[0,1,4,colorOffset,cells*4],[1,2,5,heightOffset,cells*4],[2,3,1,statusOffset,cells]] as const) { const at = 32 + index * 16; view.setUint16(at, id, true); view.setUint16(at + 2, scalar, true); view.setUint32(at + 4, offset, true); view.setUint32(at + 8, length, true); view.setUint32(at + 12, cells, true); }
  bytes.fill(1, statusOffset, statusOffset + cells); bytes.set(metadata, metadataOffset); return buffer;
}

it("严格解码地图瓦片身份、像素状态和统计", () => {
  const tile = decodeMapTile(tileBuffer());
  expect(tile.metadata).toMatchObject({ sessionId: 7, tileX: -1, tileZ: 2, blocksPerPixel: 1 });
  expect(tile.statuses[0]).toBe(1);
});

it("拒绝坐标、统计和像素状态不一致", () => {
  const source = tileBuffer(), metadataOffset = new DataView(source).getUint32(16, true), statusOffset = new DataView(source).getUint32(68, true);
  const badStatus = source.slice(0); new Uint8Array(badStatus)[statusOffset] = 0; expect(() => decodeMapTile(badStatus)).toThrow("INVALID_PROTOCOL");
  const metadata = JSON.parse(new TextDecoder().decode(new Uint8Array(source, metadataOffset))); metadata.originBlockX = 0;
  const encoded = new TextEncoder().encode(JSON.stringify(metadata)); const badMetadata = source.slice(0, metadataOffset + encoded.length); new Uint8Array(badMetadata).set(encoded, metadataOffset); const view = new DataView(badMetadata); view.setUint32(12, badMetadata.byteLength, true); view.setUint32(20, encoded.length, true);
  expect(() => decodeMapTile(badMetadata)).toThrow("INVALID_PROTOCOL");
});
