import { describe, expect, it } from "vitest";
import { summarizeMapViewport, type MapViewportProgress } from "./mapViewportCompletion";

const complete = (changes: Partial<MapViewportProgress> = {}): MapViewportProgress => ({
  targetCount: 2,
  terminalCount: 2,
  readyCount: 2,
  failedCount: 0,
  successfulChunks: 512,
  missingChunks: 0,
  unsupportedChunks: 0,
  corruptChunks: 0,
  ...changes,
});

describe("summarizeMapViewport", () => {
  it("只有全部 ready 且没有不支持、损坏或请求失败时才完整", () => {
    expect(summarizeMapViewport(complete()).kind).toBe("complete");
    expect(summarizeMapViewport(complete({ unsupportedChunks: 1 })).kind).toBe("partial-complete");
    expect(summarizeMapViewport(complete({ corruptChunks: 1 })).kind).toBe("partial-complete");
    const failed = summarizeMapViewport(complete({ readyCount: 1, failedCount: 1 }));
    expect(failed.kind).toBe("partial-complete");
    expect(failed.retryable).toBe(true);
  });

  it("真实缺失不阻止完整，全部缺失形成 empty-complete", () => {
    const mixed = summarizeMapViewport(complete({ successfulChunks: 500, missingChunks: 12 }));
    expect(mixed.kind).toBe("complete");
    expect(mixed.text).toContain("12 个真实缺失区块");
    const empty = summarizeMapViewport(complete({ successfulChunks: 0, missingChunks: 512 }));
    expect(empty.kind).toBe("empty-complete");
  });

  it("加载中与不一致计数不会伪装成完整", () => {
    expect(summarizeMapViewport(complete({ terminalCount: 1, readyCount: 1 })).kind).toBe("loading");
    expect(() => summarizeMapViewport(complete({ readyCount: 3 }))).toThrow("INVALID_RESPONSE");
  });
});
