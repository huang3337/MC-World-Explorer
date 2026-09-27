import { invalidProtocol, parseEnvelope, section } from "./common";
import type { MapTileZoom } from "./map-tile-v1";

export const MAP_BATCH_MAX_BYTES = 8 * 1024 * 1024;
export const MAP_BATCH_MAX_ITEMS = 16;
const DESCRIPTOR_WORDS = 20;
const TILE_PIXELS = 256;

export type MapBatchState = "items" | "timeout" | "ended" | "cancelled" | "shuttingDown";
export type MapFailureKind = "readFailed" | "workerPanicked" | "encodeFailed";

interface MapItemIdentity {
  tileX: number;
  tileZ: number;
  blocksPerPixel: MapTileZoom;
}

interface MapPixels {
  readonly colors: Uint32Array;
  readonly heights: Int32Array;
  readonly statuses: Uint8Array;
}

export interface MapPartialItem extends MapItemIdentity, MapPixels {
  kind: "partial";
  revision: number;
  pixelX: number;
  pixelZ: number;
  width: number;
  height: number;
  completedChunks: number;
  totalChunks: number;
}

export interface MapReadyItem extends MapItemIdentity, MapPixels {
  kind: "ready";
  successfulChunks: number;
  missingChunks: number;
  unsupportedChunks: number;
  corruptChunks: number;
}

export interface MapFailedItem extends MapItemIdentity {
  kind: "failed";
  failure: MapFailureKind;
}

export type MapBatchItem = MapPartialItem | MapReadyItem | MapFailedItem;

export interface MapBatchMetadata {
  sessionId: number;
  viewportTaskId: number;
  batchSequence: number;
  itemCount: number;
  terminalItemCount: number;
  hasMore: boolean;
  state: MapBatchState;
}

export interface MapBatch {
  readonly metadata: MapBatchMetadata;
  readonly items: readonly MapBatchItem[];
}

function integer(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max;
}

function signed(value: number): number {
  return value | 0;
}

function safeU64(low: number, high: number): number {
  const value = low + high * 0x1_0000_0000;
  if (!Number.isSafeInteger(value)) invalidProtocol();
  return value;
}

function chunkCount(blocksPerPixel: MapTileZoom): number {
  return (TILE_PIXELS * blocksPerPixel / 16) ** 2;
}

export function decodeMapBatch(buffer: ArrayBuffer): MapBatch {
  if (buffer.byteLength > MAP_BATCH_MAX_BYTES) invalidProtocol();
  const envelope = parseEnvelope(buffer, 4, [
    { id: 20, scalar: 4 },
    { id: 21, scalar: 4 },
    { id: 22, scalar: 5 },
    { id: 23, scalar: 1 },
  ]);
  const metadata = envelope.metadata;
  const keys = [
    "sessionId", "viewportTaskId", "batchSequence", "itemCount", "terminalItemCount",
    "hasMore", "state",
  ];
  if (Object.keys(metadata).length !== keys.length
    || !keys.every((key) => Object.prototype.hasOwnProperty.call(metadata, key))
    || !integer(metadata.sessionId, 1, Number.MAX_SAFE_INTEGER)
    || !integer(metadata.viewportTaskId, 1, Number.MAX_SAFE_INTEGER)
    || !integer(metadata.batchSequence, 1, Number.MAX_SAFE_INTEGER)
    || !integer(metadata.itemCount, 0, MAP_BATCH_MAX_ITEMS)
    || !integer(metadata.terminalItemCount, 0, metadata.itemCount)
    || typeof metadata.hasMore !== "boolean"
    || !["items", "timeout", "ended", "cancelled", "shuttingDown"].includes(metadata.state as string)
    || (metadata.itemCount === 0 && metadata.state === "items")
    || (metadata.itemCount > 0 && !["items", "ended"].includes(metadata.state as string))
    || (metadata.hasMore && metadata.state !== "items")) invalidProtocol();

  const descriptorsSection = section(envelope, 20, metadata.itemCount * DESCRIPTOR_WORDS);
  const colorsSection = section(envelope, 21);
  const heightsSection = section(envelope, 22, colorsSection.count);
  const statusesSection = section(envelope, 23, colorsSection.count);
  const descriptors = new Uint32Array(buffer, descriptorsSection.offset, descriptorsSection.count);
  const colors = new Uint32Array(buffer, colorsSection.offset, colorsSection.count);
  const heights = new Int32Array(buffer, heightsSection.offset, heightsSection.count);
  const statuses = new Uint8Array(buffer, statusesSection.offset, statusesSection.count);
  const items: MapBatchItem[] = [];
  const identities = new Set<string>();
  let expectedDataOffset = 0;
  let terminalItemCount = 0;

  for (let itemIndex = 0; itemIndex < metadata.itemCount; itemIndex += 1) {
    const at = itemIndex * DESCRIPTOR_WORDS;
    const kind = descriptors[at];
    const tileX = signed(descriptors[at + 1]);
    const tileZ = signed(descriptors[at + 2]);
    const blocksPerPixel = descriptors[at + 3];
    if (![1, 2, 4, 8, 16].includes(blocksPerPixel)) invalidProtocol();
    const zoom = blocksPerPixel as MapTileZoom;
    const identity = `${tileX}:${tileZ}:${zoom}`;
    if (identities.has(identity)) invalidProtocol();
    identities.add(identity);

    const revision = safeU64(descriptors[at + 4], descriptors[at + 5]);
    const pixelX = descriptors[at + 6];
    const pixelZ = descriptors[at + 7];
    const width = descriptors[at + 8];
    const height = descriptors[at + 9];
    const completedChunks = descriptors[at + 10];
    const totalChunks = descriptors[at + 11];
    const successfulChunks = descriptors[at + 12];
    const missingChunks = descriptors[at + 13];
    const unsupportedChunks = descriptors[at + 14];
    const corruptChunks = descriptors[at + 15];
    const dataOffset = descriptors[at + 16];
    const dataCount = descriptors[at + 17];
    const failureCode = descriptors[at + 18];
    const reserved = descriptors[at + 19];
    if (reserved !== 0 || dataOffset !== expectedDataOffset
      || dataOffset + dataCount > colors.length) invalidProtocol();

    if (kind === 3) {
      if (revision !== 0 || pixelX !== 0 || pixelZ !== 0 || width !== 0 || height !== 0
        || completedChunks !== 0 || totalChunks !== 0 || successfulChunks !== 0
        || missingChunks !== 0 || unsupportedChunks !== 0 || corruptChunks !== 0
        || dataCount !== 0 || ![1, 2, 3].includes(failureCode)) invalidProtocol();
      const failure: MapFailureKind = failureCode === 1
        ? "readFailed"
        : failureCode === 2 ? "workerPanicked" : "encodeFailed";
      items.push({ kind: "failed", tileX, tileZ, blocksPerPixel: zoom, failure });
      terminalItemCount += 1;
      continue;
    }

    if (failureCode !== 0 || width * height !== dataCount || width === 0 || height === 0
      || pixelX + width > TILE_PIXELS || pixelZ + height > TILE_PIXELS) invalidProtocol();
    const itemColors = colors.subarray(dataOffset, dataOffset + dataCount);
    const itemHeights = heights.subarray(dataOffset, dataOffset + dataCount);
    const itemStatuses = statuses.subarray(dataOffset, dataOffset + dataCount);
    const maxStatus = kind === 1 ? 4 : 3;
    const pixelCounts = [0, 0, 0, 0, 0];
    for (let index = 0; index < dataCount; index += 1) {
      const status = itemStatuses[index];
      if (status > maxStatus || itemColors[index] > 0x00ff_ffff
        || (status !== 0 && itemHeights[index] !== 0)) invalidProtocol();
      pixelCounts[status] += 1;
    }
    expectedDataOffset += dataCount;

    if (kind === 1) {
      const expectedChunks = chunkCount(zoom);
      if (revision === 0 || totalChunks !== expectedChunks || completedChunks > totalChunks
        || successfulChunks !== 0 || missingChunks !== 0 || unsupportedChunks !== 0
        || corruptChunks !== 0) invalidProtocol();
      items.push({
        kind: "partial", tileX, tileZ, blocksPerPixel: zoom, revision, pixelX, pixelZ,
        width, height, completedChunks, totalChunks, colors: itemColors,
        heights: itemHeights, statuses: itemStatuses,
      });
    } else if (kind === 2) {
      const expectedChunks = chunkCount(zoom);
      const pixelsPerChunk = (16 / zoom) ** 2;
      if (revision !== 0 || pixelX !== 0 || pixelZ !== 0
        || width !== TILE_PIXELS || height !== TILE_PIXELS
        || completedChunks !== expectedChunks || totalChunks !== expectedChunks
        || successfulChunks + missingChunks + unsupportedChunks + corruptChunks !== expectedChunks
        || pixelCounts[0] !== successfulChunks * pixelsPerChunk
        || pixelCounts[1] !== missingChunks * pixelsPerChunk
        || pixelCounts[2] !== unsupportedChunks * pixelsPerChunk
        || pixelCounts[3] !== corruptChunks * pixelsPerChunk) invalidProtocol();
      items.push({
        kind: "ready", tileX, tileZ, blocksPerPixel: zoom, successfulChunks,
        missingChunks, unsupportedChunks, corruptChunks, colors: itemColors,
        heights: itemHeights, statuses: itemStatuses,
      });
      terminalItemCount += 1;
    } else {
      invalidProtocol();
    }
  }
  if (expectedDataOffset !== colors.length || terminalItemCount !== metadata.terminalItemCount) {
    invalidProtocol();
  }
  return { metadata: metadata as unknown as MapBatchMetadata, items };
}
