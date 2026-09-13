import { invalidProtocol, parseEnvelope, section } from "./common";

export const MAP_TILE_PIXELS = 256;
const MAP_TILE_CELLS = MAP_TILE_PIXELS * MAP_TILE_PIXELS;
export type MapTileZoom = 1 | 2 | 4 | 8 | 16;

export interface MapTileMetadata {
  sessionId: number;
  taskId: number;
  tileX: number;
  tileZ: number;
  blocksPerPixel: MapTileZoom;
  originBlockX: number;
  originBlockZ: number;
  successfulChunks: number;
  missingChunks: number;
  unsupportedChunks: number;
  corruptChunks: number;
}

export interface MapTileData {
  readonly colors: Uint32Array;
  readonly heights: Int32Array;
  readonly statuses: Uint8Array;
  readonly metadata: MapTileMetadata;
}

function integer(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max;
}

export function decodeMapTile(buffer: ArrayBuffer): MapTileData {
  const envelope = parseEnvelope(buffer, 3, [
    { id: 1, scalar: 4 },
    { id: 2, scalar: 5 },
    { id: 3, scalar: 1 },
  ]);
  const colorsSection = section(envelope, 1, MAP_TILE_CELLS);
  const heightsSection = section(envelope, 2, MAP_TILE_CELLS);
  const statusesSection = section(envelope, 3, MAP_TILE_CELLS);
  const metadata = envelope.metadata;
  const keys = [
    "sessionId", "taskId", "tileX", "tileZ", "blocksPerPixel", "originBlockX", "originBlockZ",
    "successfulChunks", "missingChunks", "unsupportedChunks", "corruptChunks",
  ];
  if (Object.keys(metadata).length !== keys.length
    || !keys.every((key) => Object.prototype.hasOwnProperty.call(metadata, key))
    || !integer(metadata.sessionId, 1, Number.MAX_SAFE_INTEGER)
    || !integer(metadata.taskId, 1, Number.MAX_SAFE_INTEGER)
    || !integer(metadata.tileX, -0x8000_0000, 0x7fff_ffff)
    || !integer(metadata.tileZ, -0x8000_0000, 0x7fff_ffff)
    || ![1, 2, 4, 8, 16].includes(metadata.blocksPerPixel as number)) invalidProtocol();
  const blocksPerPixel = metadata.blocksPerPixel as MapTileZoom;
  const tileSize = MAP_TILE_PIXELS * blocksPerPixel;
  if (!integer(metadata.originBlockX, -0x8000_0000, 0x7fff_ffff)
    || !integer(metadata.originBlockZ, -0x8000_0000, 0x7fff_ffff)
    || metadata.originBlockX !== metadata.tileX * tileSize
    || metadata.originBlockZ !== metadata.tileZ * tileSize) invalidProtocol();
  const chunks = (tileSize / 16) ** 2;
  if (!integer(metadata.successfulChunks, 0, chunks)
    || !integer(metadata.missingChunks, 0, chunks)
    || !integer(metadata.unsupportedChunks, 0, chunks)
    || !integer(metadata.corruptChunks, 0, chunks)) invalidProtocol();
  if (metadata.successfulChunks + metadata.missingChunks
    + metadata.unsupportedChunks + metadata.corruptChunks !== chunks) invalidProtocol();
  const colors = new Uint32Array(buffer, colorsSection.offset, MAP_TILE_CELLS);
  const heights = new Int32Array(buffer, heightsSection.offset, MAP_TILE_CELLS);
  const statuses = new Uint8Array(buffer, statusesSection.offset, MAP_TILE_CELLS);
  const pixelCounts = [0, 0, 0, 0];
  for (let index = 0; index < MAP_TILE_CELLS; index += 1) {
    const status = statuses[index];
    if (status > 3 || colors[index] > 0x00ff_ffff || (status !== 0 && heights[index] !== 0)) invalidProtocol();
    pixelCounts[status] += 1;
  }
  const pixelsPerChunk = (16 / blocksPerPixel) ** 2;
  if (pixelCounts[0] !== metadata.successfulChunks * pixelsPerChunk
    || pixelCounts[1] !== metadata.missingChunks * pixelsPerChunk
    || pixelCounts[2] !== metadata.unsupportedChunks * pixelsPerChunk
    || pixelCounts[3] !== metadata.corruptChunks * pixelsPerChunk) invalidProtocol();
  return { colors, heights, statuses, metadata: metadata as unknown as MapTileMetadata };
}
