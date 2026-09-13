import { invalidProtocol, parseEnvelope, section } from "./common";

const SIZE = 144;
const CELLS = SIZE * SIZE;

type FailedSurfaceStatus = "missing" | "unsupported" | "corrupt";

export interface SurfaceWarning {
  kind: "chunkStatus";
  status: FailedSurfaceStatus;
  chunks: number;
}

export interface SurfaceMetadata {
  sessionId: number;
  taskId: number;
  originBlockX: number;
  originBlockZ: number;
  centerChunkX: number;
  centerChunkZ: number;
  centerX: number;
  centerY: number;
  centerZ: number;
  centerSource: "playerRespawn" | "worldSpawn" | "originFallback";
  worldSpawn: { x: number; y: number; z: number } | null;
  successfulChunks: number;
  failedChunks: number;
  warnings: SurfaceWarning[];
}

export interface SurfaceData {
  readonly colors: Uint32Array;
  readonly heights: Int32Array;
  readonly statuses: Uint8Array;
  readonly metadata: SurfaceMetadata;
}

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max;
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length
    && keys.every((key) => Object.prototype.hasOwnProperty.call(value, key));
}

function parseMetadata(
  value: Record<string, unknown>,
  chunkCounts: readonly number[],
  size: number,
): SurfaceMetadata {
  const keys = [
    "sessionId", "taskId", "originBlockX", "originBlockZ", "centerChunkX", "centerChunkZ",
    "centerX", "centerY", "centerZ", "centerSource", "worldSpawn",
    "successfulChunks", "failedChunks", "warnings",
  ] as const;
  if (!hasExactKeys(value, keys)
    || !isInteger(value.sessionId, 1, Number.MAX_SAFE_INTEGER)
    || !isInteger(value.taskId, 1, Number.MAX_SAFE_INTEGER)
    || !isInteger(value.originBlockX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.originBlockZ, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerChunkX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerChunkZ, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerY, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerZ, -0x8000_0000, 0x7fff_ffff)
    || !["playerRespawn", "worldSpawn", "originFallback"].includes(value.centerSource as string)
    || !(value.worldSpawn === null || (
      typeof value.worldSpawn === "object" && value.worldSpawn !== null && !Array.isArray(value.worldSpawn)
      && hasExactKeys(value.worldSpawn as Record<string, unknown>, ["x", "y", "z"])
      && isInteger((value.worldSpawn as Record<string, unknown>).x, -0x8000_0000, 0x7fff_ffff)
      && isInteger((value.worldSpawn as Record<string, unknown>).y, -0x8000_0000, 0x7fff_ffff)
      && isInteger((value.worldSpawn as Record<string, unknown>).z, -0x8000_0000, 0x7fff_ffff)
    ))
    || !isInteger(value.successfulChunks, 0, 4225)
    || !isInteger(value.failedChunks, 0, 4225)
    || !Array.isArray(value.warnings)) {
    invalidProtocol();
  }
  if (value.originBlockX !== (size === 1024 ? value.centerX - 512 : (value.centerChunkX - 4) * 16)
    || value.originBlockZ !== (size === 1024 ? value.centerZ - 512 : (value.centerChunkZ - 4) * 16)
    || value.centerChunkX !== Math.floor(value.centerX / 16)
    || value.centerChunkZ !== Math.floor(value.centerZ / 16)
    || value.successfulChunks !== chunkCounts[0]
    || value.failedChunks !== chunkCounts[2] + chunkCounts[3]
    || value.successfulChunks + chunkCounts[1] + value.failedChunks !== chunkCounts.reduce((a, b) => a + b, 0)) {
    invalidProtocol();
  }
  if (value.centerSource === "originFallback"
    && (value.centerX !== 0 || value.centerY !== 0 || value.centerZ !== 0)) invalidProtocol();
  if (value.centerSource === "worldSpawn") {
    const spawn = value.worldSpawn as Record<string, unknown> | null;
    if (!spawn || spawn.x !== value.centerX || spawn.y !== value.centerY || spawn.z !== value.centerZ) {
      invalidProtocol();
    }
  }

  const labels = ["missing", "unsupported", "corrupt"] as const;
  const expected = labels.flatMap((status, index) => (
    chunkCounts[index + 1] === 0
      ? []
      : [{ kind: "chunkStatus", status, chunks: chunkCounts[index + 1] }]
  ));
  if (value.warnings.length !== expected.length) invalidProtocol();
  for (let index = 0; index < expected.length; index += 1) {
    const warning = value.warnings[index];
    if (typeof warning !== "object" || warning === null || Array.isArray(warning)
      || !hasExactKeys(warning as Record<string, unknown>, ["kind", "status", "chunks"])
      || warning.kind !== expected[index].kind
      || warning.status !== expected[index].status
      || warning.chunks !== expected[index].chunks) {
      invalidProtocol();
    }
  }
  return value as unknown as SurfaceMetadata;
}

export function decodeSurface(buffer: ArrayBuffer): SurfaceData {
  const envelope = parseEnvelope(buffer, 1, [
    { id: 1, scalar: 4 },
    { id: 2, scalar: 5 },
    { id: 3, scalar: 1 },
  ]);
  const colorsSection = section(envelope, 1);
  const size = colorsSection.count === CELLS ? SIZE : 1024;
  const cells = size * size;
  if (colorsSection.count !== cells) invalidProtocol();
  const heightsSection = section(envelope, 2, cells);
  const statusesSection = section(envelope, 3, cells);
  const colors = new Uint32Array(buffer, colorsSection.offset, colorsSection.count);
  const heights = new Int32Array(buffer, heightsSection.offset, heightsSection.count);
  const statuses = new Uint8Array(buffer, statusesSection.offset, statusesSection.count);
  const chunkCounts = [0, 0, 0, 0];

  const ox = envelope.metadata.originBlockX;
  const oz = envelope.metadata.originBlockZ;
  if (!isInteger(ox, -0x8000_0000, 0x7fff_ffff - size + 1)
    || !isInteger(oz, -0x8000_0000, 0x7fff_ffff - size + 1)) invalidProtocol();
  for (let cz = Math.floor(oz / 16); cz <= Math.floor((oz + size - 1) / 16); cz++) {
    for (let cx = Math.floor(ox / 16); cx <= Math.floor((ox + size - 1) / 16); cx++) {
      const x0 = Math.max(0, cx * 16 - ox), z0 = Math.max(0, cz * 16 - oz);
      const x1 = Math.min(size, cx * 16 + 16 - ox), z1 = Math.min(size, cz * 16 + 16 - oz);
      const first = statuses[z0 * size + x0];
      if (first > 3) invalidProtocol();
      chunkCounts[first]++;
      for (let z = z0; z < z1; z++) for (let x = x0; x < x1; x++) {
        const index = z * size + x;
        if (statuses[index] !== first || colors[index] > 0x00ff_ffff || (first !== 0 && heights[index] !== 0)) invalidProtocol();
      }
    }
  }

  return {
    colors,
    heights,
    statuses,
    metadata: parseMetadata(envelope.metadata, chunkCounts, size),
  };
}
