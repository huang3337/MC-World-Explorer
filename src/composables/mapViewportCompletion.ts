export interface MapViewportProgress {
  targetCount: number;
  terminalCount: number;
  readyCount: number;
  failedCount: number;
  successfulChunks: number;
  missingChunks: number;
  unsupportedChunks: number;
  corruptChunks: number;
}

export type MapViewportCompletionKind =
  | "loading"
  | "complete"
  | "empty-complete"
  | "partial-complete"
  | "request-failed"
  | "cancelled"
  | "outdated"
  | "session-outdated"
  | "shutting-down";

export interface MapViewportCompletion {
  kind: MapViewportCompletionKind;
  text: string;
  retryable: boolean;
}

export function summarizeMapViewport(progress: MapViewportProgress): MapViewportCompletion {
  const values = Object.values(progress);
  if (values.some(value => !Number.isSafeInteger(value) || value < 0)
    || progress.terminalCount > progress.targetCount
    || progress.readyCount + progress.failedCount > progress.terminalCount) {
    throw new Error("INVALID_RESPONSE");
  }
  if (progress.targetCount === 0) {
    return { kind: "loading", text: "正在建立当前视口…", retryable: false };
  }
  if (progress.terminalCount < progress.targetCount) {
    return {
      kind: "loading",
      text: `当前视口：${progress.terminalCount}/${progress.targetCount} 个瓦片进入终态`,
      retryable: false,
    };
  }
  if (progress.readyCount + progress.failedCount !== progress.targetCount) {
    throw new Error("INVALID_RESPONSE");
  }
  if (progress.failedCount > 0 || progress.unsupportedChunks > 0 || progress.corruptChunks > 0) {
    const reasons = [
      progress.failedCount > 0 ? `${progress.failedCount} 个瓦片请求失败` : "",
      progress.unsupportedChunks > 0 ? `${progress.unsupportedChunks} 个区块不支持` : "",
      progress.corruptChunks > 0 ? `${progress.corruptChunks} 个区块损坏` : "",
    ].filter(Boolean);
    return {
      kind: "partial-complete",
      text: `当前视口已收束但不完整：${reasons.join("，")}`,
      retryable: progress.failedCount > 0,
    };
  }
  if (progress.successfulChunks === 0 && progress.missingChunks > 0) {
    return {
      kind: "empty-complete",
      text: `当前视口为空范围，已完整加载（${progress.missingChunks} 个真实缺失区块）`,
      retryable: false,
    };
  }
  return {
    kind: "complete",
    text: progress.missingChunks > 0
      ? `当前视口已完整加载（含 ${progress.missingChunks} 个真实缺失区块）`
      : "当前视口已完整加载",
    retryable: false,
  };
}
