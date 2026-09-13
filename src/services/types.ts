/** 字段对应 Rust AppStatusDto；路径仅用于显示，不授予文件访问能力。 */
export interface AppStatus {
  appVersion: string;
  backendStatus: "ready";
  portablePaths: PortablePaths;
}

export interface PortablePaths {
  root: string;
  cache: string;
  logs: string;
  exports: string;
  config: string;
}

export type ErrorCode =
  | "INVALID_ARGUMENTS" | "INVALID_PATH" | "REPARSE_POINT" | "NOT_DIRECTORY" | "NOT_FILE"
  | "NOT_FOUND" | "PERMISSION_DENIED" | "IN_USE" | "UNSUPPORTED_PLATFORM"
  | "IO_ERROR" | "TASK_CANCELLED" | "TASK_OUTDATED" | "TASK_ID_EXHAUSTED"
  | "INVALID_NBT" | "INVALID_REGION" | "INVALID_CHUNK" | "UNSUPPORTED_COMPRESSION"
  | "UNSUPPORTED_CHUNK" | "RESOURCE_LIMIT" | "NO_ACTIVE_WORLD" | "SESSION_OUTDATED"
  | "SESSION_ID_EXHAUSTED" | "WORKER_FAILED" | "SHUTTING_DOWN";

export interface WorldSummary {
  sessionId: number;
  name: string;
  displayPath: string;
  dataVersion: number | null;
  centerX: number;
  centerY: number;
  centerZ: number;
  centerSource: "playerRespawn" | "worldSpawn" | "originFallback";
  worldSpawn: WorldPosition | null;
}

export interface DiscoveredWorld {
  id: number;
  name: string;
  group: string;
  displayPath: string;
}

export interface WorldScan {
  rootPath: string;
  worlds: DiscoveredWorld[];
  skipped: number;
}

export interface WorldPosition {
  x: number;
  y: number;
  z: number;
}

export interface ChunkRect {
  minX: number;
  minZ: number;
  width: number;
  depth: number;
}

/** 对应 Rust AppError。 */
export interface AppError {
  code: ErrorCode;
  message: string;
  context?: "READ_WORLD";
}

/** IPC 和响应验证错误只属于前端，不新增 Rust 错误码。 */
export interface DisplayError {
  code: ErrorCode | "IPC_ERROR" | "INVALID_RESPONSE";
  message: string;
  context?: "READ_WORLD";
}
