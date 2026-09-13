import { invalidProtocol, parseEnvelope, section } from "./common";

export interface MeshBounds {
  minX: number;
  minY: number;
  minZ: number;
  maxX: number;
  maxY: number;
  maxZ: number;
}

export interface MeshWarning {
  kind: "targetUnavailable" | "neighborUnavailable" | "fallbackBlock";
  chunkX: number;
  chunkZ: number;
  failure: "missing" | "unsupported" | "corrupt" | null;
  blockName: string | null;
  occurrences: number;
}

export interface MeshMetadata {
  sessionId: number;
  taskId: number;
  chunkRect: { minX: number; minZ: number; width: number; depth: number };
  originWorldX: number;
  originWorldZ: number;
  bounds: MeshBounds | null;
  blockCount: number;
  faceCount: number;
  vertexCount: number;
  indexCount: number;
  batchCount: number;
  warnings: MeshWarning[];
}

export interface MeshData {
  readonly positions: Float32Array;
  readonly normals: Float32Array;
  readonly indices: Uint32Array;
  readonly batches: Uint32Array;
  readonly metadata: MeshMetadata;
}

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max;
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length
    && keys.every((key) => Object.prototype.hasOwnProperty.call(value, key));
}

function parseBounds(value: unknown, hasVertices: boolean): MeshBounds | null {
  if (value === null) {
    if (hasVertices) invalidProtocol();
    return null;
  }
  if (!hasVertices || typeof value !== "object" || Array.isArray(value)) invalidProtocol();
  const bounds = value as Record<string, unknown>;
  const keys = ["minX", "minY", "minZ", "maxX", "maxY", "maxZ"] as const;
  if (!hasExactKeys(bounds, keys)
    || keys.some((key) => typeof bounds[key] !== "number" || !Number.isFinite(bounds[key]))) {
    invalidProtocol();
  }
  const result = bounds as unknown as MeshBounds;
  if (result.minX > result.maxX || result.minY > result.maxY || result.minZ > result.maxZ) {
    invalidProtocol();
  }
  return result;
}

function parseWarnings(value: unknown): MeshWarning[] {
  if (!Array.isArray(value)) invalidProtocol();
  const result: MeshWarning[] = [];
  for (const item of value) {
    if (typeof item !== "object" || item === null || Array.isArray(item)) invalidProtocol();
    const warning = item as Record<string, unknown>;
    if (!hasExactKeys(warning, ["kind", "chunkX", "chunkZ", "failure", "blockName", "occurrences"])
      || !isInteger(warning.chunkX, -0x8000_0000, 0x7fff_ffff)
      || !isInteger(warning.chunkZ, -0x8000_0000, 0x7fff_ffff)
      || !isInteger(warning.occurrences, 1, 0xffff_ffff)) {
      invalidProtocol();
    }
    const unavailable = warning.kind === "targetUnavailable" || warning.kind === "neighborUnavailable";
    const fallback = warning.kind === "fallbackBlock";
    const validFailure = warning.failure === "missing"
      || warning.failure === "unsupported"
      || warning.failure === "corrupt";
    if ((!unavailable && !fallback)
      || (unavailable && (!validFailure || warning.blockName !== null))
      || (fallback && (warning.failure !== null || typeof warning.blockName !== "string"))) {
      invalidProtocol();
    }
    result.push(warning as unknown as MeshWarning);
  }
  return result;
}

function parseMetadata(value: Record<string, unknown>, vertexCount: number): MeshMetadata {
  const keys = [
    "sessionId", "taskId", "chunkRect", "originWorldX", "originWorldZ", "bounds",
    "blockCount", "faceCount", "vertexCount", "indexCount", "batchCount", "warnings",
  ] as const;
  if (!hasExactKeys(value, keys)
    || !isInteger(value.sessionId, 1, Number.MAX_SAFE_INTEGER)
    || !isInteger(value.taskId, 1, Number.MAX_SAFE_INTEGER)
    || typeof value.chunkRect !== "object" || value.chunkRect === null || Array.isArray(value.chunkRect)
    || !isInteger(value.originWorldX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.originWorldZ, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.blockCount, 0, 0xffff_ffff)
    || !isInteger(value.faceCount, 0, 0xffff_ffff)
    || !isInteger(value.vertexCount, 0, 0xffff_ffff)
    || !isInteger(value.indexCount, 0, 0xffff_ffff)
    || !isInteger(value.batchCount, 0, 0xffff_ffff)) {
    invalidProtocol();
  }
  const rect = value.chunkRect as Record<string, unknown>;
  if (!hasExactKeys(rect, ["minX", "minZ", "width", "depth"])
    || !isInteger(rect.minX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(rect.minZ, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(rect.width, 1, 8)
    || !isInteger(rect.depth, 1, 8)
    || value.originWorldX !== rect.minX * 16
    || value.originWorldZ !== rect.minZ * 16) {
    invalidProtocol();
  }
  return {
    ...(value as unknown as MeshMetadata),
    bounds: parseBounds(value.bounds, vertexCount !== 0),
    warnings: parseWarnings(value.warnings),
  };
}

function alphaFromBits(bits: number): number {
  const bytes = new ArrayBuffer(4);
  const view = new DataView(bytes);
  view.setUint32(0, bits, true);
  return view.getFloat32(0, true);
}

export function decodeMesh(buffer: ArrayBuffer): MeshData {
  const envelope = parseEnvelope(buffer, 2, [
    { id: 10, scalar: 6 },
    { id: 11, scalar: 6 },
    { id: 12, scalar: 4 },
    { id: 13, scalar: 4 },
  ]);
  const positionsSection = section(envelope, 10);
  const normalsSection = section(envelope, 11, positionsSection.count);
  const indicesSection = section(envelope, 12);
  const batchesSection = section(envelope, 13);
  if (positionsSection.count % 3 !== 0
    || indicesSection.count % 6 !== 0
    || batchesSection.count % 8 !== 0) {
    invalidProtocol();
  }

  const positions = new Float32Array(buffer, positionsSection.offset, positionsSection.count);
  const normals = new Float32Array(buffer, normalsSection.offset, normalsSection.count);
  const indices = new Uint32Array(buffer, indicesSection.offset, indicesSection.count);
  const batches = new Uint32Array(buffer, batchesSection.offset, batchesSection.count);
  const vertexCount = positions.length / 3;
  const metadata = parseMetadata(envelope.metadata, vertexCount);
  if (metadata.vertexCount !== vertexCount
    || metadata.indexCount !== indices.length
    || metadata.batchCount !== batches.length / 8
    || metadata.faceCount !== indices.length / 6) {
    invalidProtocol();
  }

  for (let index = 0; index < positions.length; index += 3) {
    const x = positions[index];
    const y = positions[index + 1];
    const z = positions[index + 2];
    const nx = normals[index];
    const ny = normals[index + 1];
    const nz = normals[index + 2];
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)
      || !Number.isFinite(nx) || !Number.isFinite(ny) || !Number.isFinite(nz)
      || (nx !== -1 && nx !== 0 && nx !== 1)
      || (ny !== -1 && ny !== 0 && ny !== 1)
      || (nz !== -1 && nz !== 0 && nz !== 1)
      || Number(nx !== 0) + Number(ny !== 0) + Number(nz !== 0) !== 1
      || metadata.bounds === null
      || x < metadata.bounds.minX || x > metadata.bounds.maxX
      || y < metadata.bounds.minY || y > metadata.bounds.maxY
      || z < metadata.bounds.minZ || z > metadata.bounds.maxZ) {
      invalidProtocol();
    }
  }
  for (const index of indices) {
    if (index >= vertexCount) invalidProtocol();
  }

  let nextIndex = 0;
  let blockCount = 0;
  let faceCount = 0;
  for (let offset = 0; offset < batches.length; offset += 8) {
    const layer = batches[offset];
    const rgb = batches[offset + 1];
    const alpha = alphaFromBits(batches[offset + 2]);
    const firstIndex = batches[offset + 3];
    const indexCount = batches[offset + 4];
    const batchBlocks = batches[offset + 5];
    const batchFaces = batches[offset + 6];
    if (layer > 4 || rgb > 0x00ff_ffff
      || !Number.isFinite(alpha) || alpha < 0 || alpha > 1
      || firstIndex !== nextIndex || indexCount % 6 !== 0
      || firstIndex + indexCount > indices.length
      || batchFaces !== indexCount / 6
      || batches[offset + 7] !== 0) {
      invalidProtocol();
    }
    nextIndex = firstIndex + indexCount;
    blockCount += batchBlocks;
    faceCount += batchFaces;
  }
  if (nextIndex !== indices.length
    || blockCount !== metadata.blockCount
    || faceCount !== metadata.faceCount) {
    invalidProtocol();
  }

  return { positions, normals, indices, batches, metadata };
}
