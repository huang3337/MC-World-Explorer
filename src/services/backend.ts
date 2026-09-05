import { invoke } from "@tauri-apps/api/core";
import type { AppStatus, DisplayError, ErrorCode } from "./types";

const messages: Record<ErrorCode | "IPC_ERROR" | "INVALID_RESPONSE", string> = {
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
  IPC_ERROR: "无法连接后端，请在桌面应用中重试",
  INVALID_RESPONSE: "后端返回的数据无效，请检查前后端版本",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
    || value.backendStatus !== "ready" || !isRecord(value.portablePaths)) return false;
  const paths = value.portablePaths;
  return ["root", "cache", "logs", "exports", "config"].every(
    (key) => typeof paths[key] === "string" && paths[key].trim().length > 0,
  );
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
