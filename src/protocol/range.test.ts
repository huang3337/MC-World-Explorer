import { expect, it } from "vitest";
import { decodeSurface } from "./surface-v1";

// Independent byte construction checked against the Rust encoder's fixed hash.
function largeSurface(): ArrayBuffer {
  const cells = 1024 * 1024;
  const metadata = new TextEncoder().encode(JSON.stringify({
    sessionId: 1, taskId: 2, originBlockX: -513, originBlockZ: -495,
    centerChunkX: -1, centerChunkZ: 1, centerX: -1, centerY: 64, centerZ: 17,
    centerSource: "worldSpawn", worldSpawn: { x: -1, y: 64, z: 17 },
    successfulChunks: 0, failedChunks: 0, warnings: [{ kind: "chunkStatus", status: "missing", chunks: 4225 }],
  }));
  const offset = 80 + cells * 9;
  const buffer = new ArrayBuffer(offset + metadata.length);
  const bytes = new Uint8Array(buffer); const v = new DataView(buffer);
  bytes.set([77, 67, 87, 69]); v.setUint16(4, 1, true); v.setUint16(6, 1, true);
  for (const [at, value] of [[8, 32], [12, buffer.byteLength], [16, offset], [20, metadata.length], [24, 32], [28, 3]]) v.setUint32(at, value, true);
  let cursor = 80;
  for (const [index, scalar, width] of [[0, 4, 4], [1, 5, 4], [2, 1, 1]]) {
    const at = 32 + index * 16;
    v.setUint16(at, index + 1, true); v.setUint16(at + 2, scalar, true);
    v.setUint32(at + 4, cursor, true); v.setUint32(at + 8, cells * width, true); v.setUint32(at + 12, cells, true);
    if (index === 2) bytes.fill(1, cursor, cursor + cells);
    cursor += cells * width;
  }
  bytes.set(metadata, offset); return buffer;
}

it("1024 二维独立字节匹配 Rust 黄金哈希，支持非对齐边缘并拒绝局部状态不一致", () => {
  const buffer = largeSurface();
  let hash = 0xcbf29ce484222325n;
  for (const byte of new Uint8Array(buffer)) hash = ((hash ^ BigInt(byte)) * 0x100000001b3n) & 0xffffffffffffffffn;
  expect(hash).toBe(252811299370147227n);
  const data = decodeSurface(buffer);
  expect(data.colors.length).toBe(1048576);
  expect(data.metadata.warnings[0].chunks).toBe(4225);
  data.statuses[data.statuses.length - 1] = 0;
  expect(() => decodeSurface(buffer)).toThrow("INVALID_PROTOCOL");
});
