import { describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import surfaceGoldenBase64 from "../protocol/fixtures/surface-v1.bin.gz.b64?raw";
import meshGoldenBase64 from "../protocol/fixtures/mesh-v1.bin.gz.b64?raw";
import mapBatchGoldenBase64 from "../protocol/fixtures/map-batch-v1.bin.gz.b64?raw";
import {
  buildMesh,
  cancelActiveTask,
  cancelMapViewport,
  getAppStatus,
  loadSurface,
  selectWorld,
  scanWorldDirectory,
  openDiscoveredWorld,
  nextMapViewportBatch,
  startMapViewport,
  toDisplayError,
} from "./backend";

const status = {
  appVersion: "0.7.3", backendStatus: "ready",
  portablePaths: { root: "D:\\MCWE", cache: "D:\\MCWE\\cache", logs: "D:\\MCWE\\logs", exports: "D:\\MCWE\\exports", config: "D:\\MCWE\\config" },
};

const world = {
  sessionId: 7,
  name: "合成世界",
  displayPath: "D:\\World",
  dataVersion: null,
  centerX: -17,
  centerY: 64,
  centerZ: -33,
  centerSource: "worldSpawn",
  worldSpawn: { x: -17, y: 64, z: -33 },
  loadAnchor: { x: -120, y: 72, z: 48 },
  loadAnchorSource: "playerExit",
};

async function inflateGolden(encoded: string): Promise<ArrayBuffer> {
  const compressed = Uint8Array.from(atob(encoded.trim()), (value) => value.charCodeAt(0));
  const stream = new Response(compressed.buffer).body!
    .pipeThrough(new DecompressionStream("gzip"));
  return new Response(stream).arrayBuffer();
}

const surfaceGolden = await inflateGolden(surfaceGoldenBase64);
const meshGolden = await inflateGolden(meshGoldenBase64);
const mapBatchGolden = await inflateGolden(mapBatchGoldenBase64);

describe("backend", () => {
  it("严格验证视口启动摘要并解码归属一致的批次", async () => {
    const summary = { sessionId: 7, viewportTaskId: 9, blocksPerPixel: 1, targetCount: 2, targets: [{ tileX: -1, tileZ: 2, blocksPerPixel: 1 }, { tileX: 1, tileZ: 2, blocksPerPixel: 1 }] };
    vi.mocked(invoke).mockResolvedValueOnce(summary).mockResolvedValueOnce(mapBatchGolden.slice(0));
    expect(await startMapViewport(7, -1, 2, 800, 520, 1, 0.5)).toEqual(summary);
    expect(invoke).toHaveBeenCalledWith("start_map_viewport", { sessionId: 7, centerX: -1, centerZ: 2, viewportWidth: 800, viewportHeight: 520, blocksPerPixel: 1, displayBlocksPerPixel: 0.5 });
    const batch = await nextMapViewportBatch(7, 9);
    expect(batch.metadata).toMatchObject({ sessionId: 7, viewportTaskId: 9, itemCount: 2 });
  });

  it("拒绝非法视口参数、重复目标和批次身份错配", async () => {
    await expect(startMapViewport(7, 0, 0, 0, 520, 1, 1)).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    vi.mocked(invoke).mockResolvedValueOnce({ sessionId: 7, viewportTaskId: 9, blocksPerPixel: 1, targetCount: 2, targets: [{ tileX: 0, tileZ: 0, blocksPerPixel: 1 }, { tileX: 0, tileZ: 0, blocksPerPixel: 1 }] });
    await expect(startMapViewport(7, 0, 0, 800, 520, 1, 1)).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
    vi.mocked(invoke).mockResolvedValueOnce(mapBatchGolden.slice(0));
    await expect(nextMapViewportBatch(7, 10)).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
  it("视口请求分别校验显示倍率和瓦片倍率", async () => {
    await expect(startMapViewport(7, 0, 0, 800, 520, 1, Number.NaN)).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    await expect(startMapViewport(7, 0, 0, 800, 520, 1, 0.1)).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    expect(invoke).not.toHaveBeenCalled();
  });
  it("地图取消只提交会话与视口任务 ID，拒绝非法 ID", async () => {
    vi.mocked(invoke).mockResolvedValueOnce(null);
    await expect(cancelMapViewport(7, 9)).resolves.toBeUndefined();
    expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 7, viewportTaskId: 9 });
    vi.mocked(invoke).mockClear();
    await expect(cancelMapViewport(0, 9)).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    await expect(cancelMapViewport(7, Number.MAX_SAFE_INTEGER + 1)).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    expect(invoke).not.toHaveBeenCalled();
  });
  it("目录扫描校验候选，打开时只发送编号", async () => {
    const entry = { id: 42, name: "雪山", group: "实例", displayPath: "D:\\World" };
    const scan = { rootPath: "D:\\versions", worlds: [entry], skipped: 0 };
    vi.mocked(invoke).mockResolvedValueOnce(scan).mockResolvedValueOnce(world);
    expect(await scanWorldDirectory()).toEqual(scan);
    expect(invoke).toHaveBeenCalledWith("scan_world_directory");
    expect(await openDiscoveredWorld(42)).toEqual(world);
    expect(invoke).toHaveBeenCalledWith("open_discovered_world", { worldId: 42 });
    vi.mocked(invoke).mockResolvedValueOnce({ ...scan, worlds: [entry, entry] });
    await expect(scanWorldDirectory()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
    vi.mocked(invoke).mockResolvedValueOnce(null);
    expect(await scanWorldDirectory()).toBeNull();
    vi.mocked(invoke).mockClear();
    await expect(openDiscoveredWorld(Number.MAX_SAFE_INTEGER + 1)).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    expect(invoke).not.toHaveBeenCalled();
  });
  it("只通过 app_status 获取无参数状态", async () => {
    vi.mocked(invoke).mockResolvedValue(status);
    expect(await getAppStatus()).toEqual(status);
    expect(invoke).toHaveBeenCalledExactlyOnceWith("app_status");
  });

  it.each([
    null,
    {},
    { ...status, backendStatus: "unknown" },
    { ...status, portablePaths: { root: "D:\\" } },
    { ...status, unexpected: true },
  ])("拒绝不完整、未知或额外成功响应 %j", async (value) => {
    vi.mocked(invoke).mockResolvedValue(value);
    await expect(getAppStatus()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("后端错误只显示固定中文，不信任附带的内部文本", async () => {
    vi.mocked(invoke).mockRejectedValue({ code: "PERMISSION_DENIED", message: "C:\\secret stack", context: "READ_WORLD" });
    await expect(getAppStatus()).rejects.toEqual({ code: "PERMISSION_DENIED", message: "没有读取来源的权限", context: "READ_WORLD" });
  });

  it.each([new Error("C:\\secret"), "internal stack", null, { code: "toString", message: "secret" }])("未知异常安全降级 %j", (value) => {
    expect(toDisplayError(value)).toEqual({ code: "IPC_ERROR", message: "无法连接后端，请在桌面应用中重试" });
  });

  it("选择世界只使用无参数 Command，并验证 null 或完整摘要", async () => {
    vi.mocked(invoke).mockResolvedValueOnce(world).mockResolvedValueOnce(null);
    await expect(selectWorld()).resolves.toEqual(world);
    await expect(selectWorld()).resolves.toBeNull();
    expect(invoke).toHaveBeenNthCalledWith(1, "select_world");
    expect(invoke).toHaveBeenNthCalledWith(2, "select_world");

    vi.mocked(invoke).mockResolvedValue({ ...world, sessionId: Number.MAX_SAFE_INTEGER + 1 });
    await expect(selectWorld()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    vi.mocked(invoke).mockResolvedValue({ ...world, worldSpawn: null });
    await expect(selectWorld()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    vi.mocked(invoke).mockResolvedValue({ ...world, centerSource: "originFallback" });
    await expect(selectWorld()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    vi.mocked(invoke).mockResolvedValue({ ...world, loadAnchorSource: "worldSpawn" });
    await expect(selectWorld()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    vi.mocked(invoke).mockResolvedValue({ ...world, loadAnchor: { x: 0, y: 64, z: 0 }, loadAnchorSource: "originFallback" });
    await expect(selectWorld()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("二维和三维只发送 sessionId 与固定 chunkRect，并解码 raw ArrayBuffer", async () => {
    vi.mocked(invoke)
      .mockResolvedValueOnce(surfaceGolden.slice(0))
      .mockResolvedValueOnce(meshGolden.slice(0));
    const surface = await loadSurface(11);
    const mesh = await buildMesh(21, { minX: -1, minZ: 2, width: 1, depth: 1 });
    expect(surface.metadata.sessionId).toBe(11);
    expect(mesh.metadata.sessionId).toBe(21);
    expect(invoke).toHaveBeenNthCalledWith(1, "load_surface", { sessionId: 11 });
    expect(invoke).toHaveBeenNthCalledWith(2, "build_mesh_for_selection", {
      sessionId: 21,
      chunkRect: { minX: -1, minZ: 2, width: 1, depth: 1 },
    });
  });

  it.each([
    ["session 0", () => loadSurface(0)],
    ["unsafe session", () => loadSurface(Number.MAX_SAFE_INTEGER + 1)],
    ["fractional chunk", () => buildMesh(1, { minX: 0.5, minZ: 0, width: 1, depth: 1 })],
    ["invalid width", () => buildMesh(1, { minX: 0, minZ: 0, width: 9, depth: 1 })],
    ["extra path", () => buildMesh(1, {
      minX: 0, minZ: 0, width: 1, depth: 1, path: "D:\\untrusted",
    } as never)],
  ])("无效前端参数在 invoke 前拒绝：%s", async (_name, call) => {
    await expect(call()).rejects.toMatchObject({ code: "INVALID_ARGUMENTS" });
    expect(invoke).not.toHaveBeenCalled();
  });

  it.each(["TASK_CANCELLED", "TASK_OUTDATED", "SESSION_OUTDATED"] as const)(
    "保留稳定业务错误 %s，不误报为协议错误",
    async (code) => {
      vi.mocked(invoke).mockRejectedValue({ code, message: "untrusted" });
      await expect(loadSurface(1)).rejects.toMatchObject({ code });
    },
  );

  it("畸形 raw 响应映射为固定 INVALID_RESPONSE", async () => {
    vi.mocked(invoke).mockResolvedValue(new ArrayBuffer(0));
    await expect(loadSurface(1)).rejects.toEqual({
      code: "INVALID_RESPONSE",
      message: "后端返回的数据无效，请检查前后端版本",
    });
  });

  it("取消使用无参数 Command，验证小型 JSON 且保持幂等状态", async () => {
    vi.mocked(invoke)
      .mockResolvedValueOnce({ cancelled: true })
      .mockResolvedValueOnce({ cancelled: false });
    await expect(cancelActiveTask()).resolves.toBe(true);
    await expect(cancelActiveTask()).resolves.toBe(false);
    expect(invoke).toHaveBeenNthCalledWith(1, "cancel_active_task");
    expect(invoke).toHaveBeenNthCalledWith(2, "cancel_active_task");

    vi.mocked(invoke).mockResolvedValue({ cancelled: "yes" });
    await expect(cancelActiveTask()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
});
