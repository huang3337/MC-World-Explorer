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
  | "INVALID_PATH" | "REPARSE_POINT" | "NOT_DIRECTORY" | "NOT_FILE"
  | "NOT_FOUND" | "PERMISSION_DENIED" | "IN_USE" | "UNSUPPORTED_PLATFORM"
  | "IO_ERROR" | "TASK_CANCELLED" | "TASK_OUTDATED" | "TASK_ID_EXHAUSTED";

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
