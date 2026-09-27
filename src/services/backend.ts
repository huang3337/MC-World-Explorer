import { invoke } from "@tauri-apps/api/core";
import type { AppStatus, ChunkRect, DisplayError, ErrorCode, MapViewportSummary, WorldSummary, WorldScan } from "./types";
import { decodeSurface, type SurfaceData } from "../protocol/surface-v1";
import { decodeMesh, type MeshData } from "../protocol/mesh-v1";
import { decodeMapTile, type MapTileData, type MapTileZoom } from "../protocol/map-tile-v1";
import { decodeMapBatch, type MapBatch } from "../protocol/map-batch-v1";

const messages: Record<ErrorCode | "IPC_ERROR" | "INVALID_RESPONSE", string> = {
  INVALID_ARGUMENTS: "请求参数无效",
  INVALID_PATH: "路径无效",
  REPARSE_POINT: "路径包含不允许跟随的链接或重解析点",
  NOT_DIRECTORY: "来源路径不是目录",
  NOT_FILE: "读取目标不是普通文件",
  NOT_FOUND: "读取来源不存在",
  PERMISSION_DENIED: "没有读取来源的权限",
  IN_USE: "来源正在被其他文件操作占用",
  UNSUPPORTED_PLATFORM: "当前平台尚不支持此操作",
  IO_ERROR: "读取来源时发生 I/O 错误",
  TASK_CANCELLED: "任务已取消",
  TASK_OUTDATED: "任务结果已过期",
  TASK_ID_EXHAUSTED: "任务编号已耗尽，无法启动新任务",
  INVALID_NBT: "NBT 数据无效",
  INVALID_REGION: "Region 文件结构无效",
  INVALID_CHUNK: "区块数据无效",
  UNSUPPORTED_COMPRESSION: "数据使用了不支持的压缩方式",
  UNSUPPORTED_CHUNK: "区块格式不在当前版本支持范围内",
  RESOURCE_LIMIT: "数据超过安全资源上限",
  NO_ACTIVE_WORLD: "尚未选择有效世界",
  SESSION_OUTDATED: "世界会话已过期",
  SESSION_ID_EXHAUSTED: "世界会话编号已耗尽，无法选择新世界",
  WORKER_FAILED: "后台任务异常终止",
  SHUTTING_DOWN: "应用正在关闭，无法启动新任务",
  IPC_ERROR: "无法连接后端，请在桌面应用中重试",
  INVALID_RESPONSE: "后端返回的数据无效，请检查前后端版本",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max;
}

function isWorldPosition(value: unknown): boolean {
  return isRecord(value)
    && Object.keys(value).length === 3
    && ["x", "y", "z"].every((key) => Object.prototype.hasOwnProperty.call(value, key))
    && isInteger(value.x, -0x8000_0000, 0x7fff_ffff)
    && isInteger(value.y, -0x8000_0000, 0x7fff_ffff)
    && isInteger(value.z, -0x8000_0000, 0x7fff_ffff);
}

export function toDisplayError(value: unknown): DisplayError {
  const code = isRecord(value) && typeof value.code === "string"
    && Object.prototype.hasOwnProperty.call(messages, value.code)
    ? value.code as DisplayError["code"] : "IPC_ERROR";
  return {
    code,
    message: messages[code],
    ...(isRecord(value) && value.context === "READ_WORLD" ? { context: "READ_WORLD" as const } : {}),
  };
}

function isAppStatus(value: unknown): value is AppStatus {
  if (!isRecord(value) || typeof value.appVersion !== "string" || !value.appVersion.trim()
    || value.backendStatus !== "ready" || !isRecord(value.portablePaths)
    || Object.keys(value).length !== 3) return false;
  const paths = value.portablePaths;
  const keys = ["root", "cache", "logs", "exports", "config"];
  return Object.keys(paths).length === keys.length && keys.every(
    (key) => typeof paths[key] === "string" && paths[key].trim().length > 0,
  );
}

function isWorldSummary(value: unknown): value is WorldSummary {
  if (!isRecord(value)
    || !isInteger(value.sessionId, 1, Number.MAX_SAFE_INTEGER)
    || typeof value.name !== "string"
    || typeof value.displayPath !== "string" || !value.displayPath.trim()
    || !(value.dataVersion === null || isInteger(value.dataVersion, -0x8000_0000, 0x7fff_ffff))
    || !isInteger(value.centerX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerY, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(value.centerZ, -0x8000_0000, 0x7fff_ffff)
    || !["playerRespawn", "worldSpawn", "originFallback"].includes(value.centerSource as string)
    || !(value.worldSpawn === null || isWorldPosition(value.worldSpawn))
    || !isWorldPosition(value.loadAnchor)
    || !["playerExit", "worldSpawn", "originFallback"].includes(value.loadAnchorSource as string)) {
    return false;
  }
  const expected = [
    "sessionId", "name", "displayPath", "dataVersion", "centerX", "centerY", "centerZ", "centerSource", "worldSpawn",
    "loadAnchor", "loadAnchorSource",
  ];
  if (Object.keys(value).length !== expected.length
    || !expected.every((key) => Object.prototype.hasOwnProperty.call(value, key))) return false;
  const loadAnchor = value.loadAnchor;
  if (!isRecord(loadAnchor)) return false;
  if (value.loadAnchorSource === "originFallback"
    && (loadAnchor.x !== 0 || loadAnchor.y !== 0 || loadAnchor.z !== 0)) return false;
  if (value.loadAnchorSource === "worldSpawn") {
    const spawn = value.worldSpawn;
    if (!isRecord(spawn)
      || spawn.x !== loadAnchor.x || spawn.y !== loadAnchor.y || spawn.z !== loadAnchor.z) return false;
  }
  if (value.centerSource === "originFallback") {
    return value.centerX === 0 && value.centerY === 0 && value.centerZ === 0;
  }
  if (value.centerSource === "worldSpawn") {
    const spawn = value.worldSpawn;
    return isRecord(spawn)
      && spawn.x === value.centerX && spawn.y === value.centerY && spawn.z === value.centerZ;
  }
  return true;
}

function validSessionId(value: number): boolean {
  return isInteger(value, 1, Number.MAX_SAFE_INTEGER);
}

function validChunkRect(value: ChunkRect): boolean {
  return isRecord(value)
    && Object.keys(value).length === 4
    && ["minX", "minZ", "width", "depth"]
      .every((key) => Object.prototype.hasOwnProperty.call(value, key))
    && isInteger(value.minX, -0x8000_0000, 0x7fff_ffff)
    && isInteger(value.minZ, -0x8000_0000, 0x7fff_ffff)
    && isInteger(value.width, 1, 8)
    && isInteger(value.depth, 1, 8);
}

function invalidArguments(): DisplayError {
  return toDisplayError({ code: "INVALID_ARGUMENTS" });
}

/** 前端唯一原始 IPC 入口；不访问文件系统，不记录内部错误文本。 */
export async function getAppStatus(): Promise<AppStatus> {
  try {
    const value: unknown = await invoke("app_status");
    if (!isAppStatus(value)) throw { code: "INVALID_RESPONSE" };
    return value;
  } catch (error) {
    throw toDisplayError(error);
  }
}

export async function selectWorld(): Promise<WorldSummary | null> {
  try {
    const value: unknown = await invoke("select_world");
    if (value !== null && !isWorldSummary(value)) throw new Error("INVALID_RESPONSE");
    return value;
  }
  catch (error) {
    throw error instanceof Error
      ? toDisplayError({ code: "INVALID_RESPONSE" })
      : toDisplayError(error);
  }
}

export async function scanWorldDirectory(): Promise<WorldScan | null> {
  try {
    const value: unknown = await invoke("scan_world_directory");
    if (value === null) return null;
    if (!isRecord(value) || Object.keys(value).length !== 3
      || typeof value.rootPath !== "string" || !value.rootPath.trim()
      || !isInteger(value.skipped, 0, Number.MAX_SAFE_INTEGER)
      || !Array.isArray(value.worlds) || value.worlds.length > 4096) throw new Error("INVALID_RESPONSE");
    const ids = new Set<number>();
    for (const world of value.worlds) {
      if (!isRecord(world) || Object.keys(world).length !== 4
        || !isInteger(world.id, 1, Number.MAX_SAFE_INTEGER) || ids.has(world.id)
        || typeof world.name !== "string" || typeof world.group !== "string"
        || typeof world.displayPath !== "string" || !world.displayPath.trim()) throw new Error("INVALID_RESPONSE");
      ids.add(world.id);
    }
    return value as unknown as WorldScan;
  } catch (error) {
    throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error);
  }
}

export async function openDiscoveredWorld(worldId: number): Promise<WorldSummary | null> {
  if (!validSessionId(worldId)) throw invalidArguments();
  try {
    const value: unknown = await invoke("open_discovered_world", { worldId });
    if (!isWorldSummary(value)) throw new Error("INVALID_RESPONSE");
    return value;
  } catch (error) {
    throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error);
  }
}

export async function loadSurface(sessionId: number): Promise<SurfaceData> {
  if (!validSessionId(sessionId)) throw invalidArguments();
  try { return decodeSurface(await invoke<ArrayBuffer>("load_surface", { sessionId })); }
  catch (error) { throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error); }
}

export async function loadMapTile(
  sessionId: number,
  tileX: number,
  tileZ: number,
  blocksPerPixel: MapTileZoom,
): Promise<MapTileData> {
  if (!validSessionId(sessionId)
    || !isInteger(tileX, -0x8000_0000, 0x7fff_ffff)
    || !isInteger(tileZ, -0x8000_0000, 0x7fff_ffff)
    || ![1, 2, 4, 8, 16].includes(blocksPerPixel)) throw invalidArguments();
  const size = 256 * blocksPerPixel;
  if (tileX * size < -0x8000_0000 || tileX * size + size > 0x8000_0000
    || tileZ * size < -0x8000_0000 || tileZ * size + size > 0x8000_0000) throw invalidArguments();
  try {
    return decodeMapTile(await invoke<ArrayBuffer>("load_map_tile", {
      sessionId, tileX, tileZ, blocksPerPixel,
    }));
  } catch (error) {
    throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error);
  }
}

export async function startMapViewport(
  sessionId: number,
  centerX: number,
  centerZ: number,
  viewportWidth: number,
  viewportHeight: number,
  blocksPerPixel: MapTileZoom,
  displayBlocksPerPixel: number,
): Promise<MapViewportSummary> {
  if (!validSessionId(sessionId)
    || !Number.isFinite(centerX) || !Number.isFinite(centerZ)
    || !isInteger(viewportWidth, 1, 16_384)
    || !isInteger(viewportHeight, 1, 16_384)
    || ![1, 2, 4, 8, 16].includes(blocksPerPixel)
    || !Number.isFinite(displayBlocksPerPixel)
    || displayBlocksPerPixel < 0.25 || displayBlocksPerPixel > 16) throw invalidArguments();
  try {
    const value: unknown = await invoke("start_map_viewport", {
      sessionId, centerX, centerZ, viewportWidth, viewportHeight, blocksPerPixel, displayBlocksPerPixel,
    });
    if (!isRecord(value)
      || Object.keys(value).length !== 5
      || !validSessionId(value.sessionId as number)
      || value.sessionId !== sessionId
      || !validSessionId(value.viewportTaskId as number)
      || value.blocksPerPixel !== blocksPerPixel
      || !isInteger(value.targetCount, 1, 64)
      || !Array.isArray(value.targets)
      || value.targets.length !== value.targetCount) throw new Error("INVALID_RESPONSE");
    const identities = new Set<string>();
    for (const target of value.targets) {
      if (!isRecord(target) || Object.keys(target).length !== 3
        || !isInteger(target.tileX, -0x8000_0000, 0x7fff_ffff)
        || !isInteger(target.tileZ, -0x8000_0000, 0x7fff_ffff)
        || target.blocksPerPixel !== blocksPerPixel) throw new Error("INVALID_RESPONSE");
      const identity = `${blocksPerPixel}:${target.tileX}:${target.tileZ}`;
      if (identities.has(identity)) throw new Error("INVALID_RESPONSE");
      identities.add(identity);
    }
    return value as unknown as MapViewportSummary;
  } catch (error) {
    throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error);
  }
}

export async function nextMapViewportBatch(
  sessionId: number,
  viewportTaskId: number,
): Promise<MapBatch> {
  if (!validSessionId(sessionId) || !validSessionId(viewportTaskId)) throw invalidArguments();
  try {
    const batch = decodeMapBatch(await invoke<ArrayBuffer>("next_map_viewport_batch", {
      sessionId, viewportTaskId,
    }));
    if (batch.metadata.sessionId !== sessionId
      || batch.metadata.viewportTaskId !== viewportTaskId) throw new Error("INVALID_RESPONSE");
    return batch;
  } catch (error) {
    throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error);
  }
}

export async function cancelMapViewport(sessionId: number, viewportTaskId: number): Promise<void> {
  if (!validSessionId(sessionId) || !validSessionId(viewportTaskId)) throw invalidArguments();
  try {
    const response: unknown = await invoke("cancel_map_viewport", { sessionId, viewportTaskId });
    if (response !== null) throw new Error("INVALID_RESPONSE");
  } catch (error) {
    throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error);
  }
}

export async function buildMesh(sessionId: number, chunkRect: ChunkRect): Promise<MeshData> {
  if (!validSessionId(sessionId) || !validChunkRect(chunkRect)) throw invalidArguments();
  try { return decodeMesh(await invoke<ArrayBuffer>("build_mesh_for_selection", { sessionId, chunkRect })); }
  catch (error) { throw error instanceof Error ? toDisplayError({ code: "INVALID_RESPONSE" }) : toDisplayError(error); }
}

export async function cancelActiveTask(): Promise<boolean> {
  try {
    const value: unknown = await invoke("cancel_active_task");
    if (!isRecord(value)
      || Object.keys(value).length !== 1
      || typeof value.cancelled !== "boolean") {
      throw new Error("INVALID_RESPONSE");
    }
    return value.cancelled;
  }
  catch (error) {
    throw error instanceof Error
      ? toDisplayError({ code: "INVALID_RESPONSE" })
      : toDisplayError(error);
  }
}
