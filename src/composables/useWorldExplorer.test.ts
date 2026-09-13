import { expect, it, vi } from "vitest";
import type { MeshData } from "../protocol/mesh-v1";
import type { SurfaceData } from "../protocol/surface-v1";
import type { AppStatus, DisplayError, WorldSummary } from "../services/types";
import { useWorldExplorer, type WorldExplorerBackend } from "./useWorldExplorer";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

const appStatus: AppStatus = {
  appVersion: "0.7.2",
  backendStatus: "ready",
  portablePaths: { root: "root", cache: "cache", logs: "logs", exports: "exports", config: "config" },
};

function makeWorld(sessionId: number): WorldSummary {
  return { sessionId, name: `世界${sessionId}`, displayPath: `D:\\World${sessionId}`, dataVersion: null, centerX: 0, centerY: 64, centerZ: 0, centerSource: "worldSpawn", worldSpawn: { x: 0, y: 64, z: 0 } };
}

function makeSurface(sessionId: number, failedChunks = 0): SurfaceData {
  return {
    colors: new Uint32Array(144 * 144),
    heights: new Int32Array(144 * 144),
    statuses: new Uint8Array(144 * 144),
    metadata: {
      sessionId, taskId: sessionId, originBlockX: -64, originBlockZ: -64,
      centerChunkX: 0, centerChunkZ: 0, centerX: 0, centerY: 64, centerZ: 0,
      centerSource: "worldSpawn", worldSpawn: { x: 0, y: 64, z: 0 },
      successfulChunks: 81 - failedChunks, failedChunks, warnings: [],
    },
  };
}

function makeMesh(sessionId: number, warnings = 0): MeshData {
  return {
    positions: new Float32Array(), normals: new Float32Array(), indices: new Uint32Array(), batches: new Uint32Array(),
    metadata: {
      sessionId, taskId: sessionId, chunkRect: { minX: 0, minZ: 0, width: 1, depth: 1 },
      originWorldX: 0, originWorldZ: 0, bounds: null, blockCount: 0, faceCount: 0,
      vertexCount: 0, indexCount: 0, batchCount: 0,
      warnings: warnings ? [{ kind: "targetUnavailable", chunkX: 0, chunkZ: 0, failure: "corrupt", blockName: null, occurrences: 1 }] : [],
    },
  };
}

it("缺失目标和视觉回退不把三维结果升级为部分失败", async () => {
  const api = backend();
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();

  const missing = makeMesh(1, 1);
  missing.metadata.warnings[0].failure = "missing";
  vi.mocked(api.buildMesh).mockResolvedValueOnce(missing);
  await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  expect(explorer.phase.value).toBe("mesh-ready");

  const fallback = makeMesh(1, 1);
  fallback.metadata.warnings[0] = {
    kind: "fallbackBlock", chunkX: 0, chunkZ: 0, failure: null,
    blockName: "example:block", occurrences: 1,
  };
  vi.mocked(api.buildMesh).mockResolvedValueOnce(fallback);
  await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  expect(explorer.phase.value).toBe("mesh-ready");
});

function backend(overrides: Partial<WorldExplorerBackend> = {}): WorldExplorerBackend {
  return {
    getAppStatus: vi.fn().mockResolvedValue(appStatus),
    selectWorld: vi.fn().mockResolvedValue(makeWorld(1)),
    scanWorldDirectory: vi.fn().mockResolvedValue(null),
    openDiscoveredWorld: vi.fn().mockResolvedValue(makeWorld(1)),
    loadSurface: vi.fn().mockResolvedValue(makeSurface(1)),
    buildMesh: vi.fn().mockResolvedValue(makeMesh(1)),
    cancelActiveTask: vi.fn().mockResolvedValue(true),
    toDisplayError: (reason) => reason as DisplayError,
    ...overrides,
  };
}

it("扫描目录保留旧视图，列表选择只提交编号，取消目录选择保留列表", async () => {
  const api = backend();
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();
  const previousSurface = explorer.surface.value;
  const catalog = { rootPath: "D:\\MC", worlds: [{ id: 9, name: "世界2", group: "实例", displayPath: "D:\\MC\\saves\\世界2" }], skipped: 0 };
  vi.mocked(api.scanWorldDirectory).mockResolvedValueOnce(catalog);
  await explorer.scanDirectory();
  expect(explorer.library.value).toEqual(catalog);
  expect(explorer.surface.value).toBe(previousSurface);
  vi.mocked(api.openDiscoveredWorld).mockResolvedValueOnce(makeWorld(2));
  vi.mocked(api.loadSurface).mockResolvedValueOnce(makeSurface(2));
  await explorer.chooseWorld(9);
  expect(api.openDiscoveredWorld).toHaveBeenCalledWith(9);
  expect(explorer.world.value?.sessionId).toBe(2);
  await explorer.scanDirectory();
  expect(explorer.library.value).toEqual(catalog);
});

it("新世界二维完整解码前保留旧完整视图并禁用交互，提交时原子清除旧三维", async () => {
  const api = backend();
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();
  await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  const oldSurface = explorer.surface.value;
  const oldMesh = explorer.mesh.value;

  const nextSurface = deferred<SurfaceData>();
  vi.mocked(api.selectWorld).mockResolvedValueOnce(makeWorld(2));
  vi.mocked(api.loadSurface).mockReturnValueOnce(nextSurface.promise);
  const switching = explorer.chooseWorld();
  await vi.waitFor(() => expect(explorer.phase.value).toBe("surface-loading"));

  expect(explorer.world.value?.sessionId).toBe(1);
  expect(explorer.surface.value).toBe(oldSurface);
  expect(explorer.mesh.value).toBe(oldMesh);
  expect(explorer.hasRetainedView.value).toBe(true);
  expect(explorer.canInteractWithSurface.value).toBe(false);

  nextSurface.resolve(makeSurface(2));
  await switching;
  expect(explorer.world.value?.sessionId).toBe(2);
  expect(explorer.surface.value?.metadata.sessionId).toBe(2);
  expect(explorer.mesh.value).toBeNull();
  expect(explorer.hasRetainedView.value).toBe(false);
  expect(explorer.canInteractWithSurface.value).toBe(true);
});

it("取消三维后忽略迟到结果并保留此前完整三维", async () => {
  const api = backend();
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();
  await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  const oldMesh = explorer.mesh.value;
  const lateMesh = deferred<MeshData>();
  vi.mocked(api.buildMesh).mockReturnValueOnce(lateMesh.promise);

  const request = explorer.selectRect({ minX: 1, minZ: 1, width: 1, depth: 1 });
  expect(explorer.phase.value).toBe("mesh-loading");
  await explorer.cancel();
  expect(explorer.phase.value).toBe("mesh-cancelled");
  expect(explorer.mesh.value).toBe(oldMesh);
  lateMesh.resolve(makeMesh(1, 1));
  await request;
  expect(explorer.mesh.value).toBe(oldMesh);
  expect(api.cancelActiveTask).toHaveBeenCalledTimes(1);
});

it("取消 IPC 失败时显示业务错误，并继续拒绝迟到结果", async () => {
  const lateMesh = deferred<MeshData>();
  const api = backend({
    buildMesh: vi.fn().mockReturnValue(lateMesh.promise),
    cancelActiveTask: vi.fn().mockRejectedValue({ code: "IPC_ERROR", message: "无法连接后端" }),
  });
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();
  const request = explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  await explorer.cancel();
  expect(explorer.phase.value).toBe("mesh-error");
  expect(explorer.businessError.value?.code).toBe("IPC_ERROR");
  lateMesh.resolve(makeMesh(1));
  await request;
  expect(explorer.mesh.value).toBeNull();
});

it("区分部分成功、业务失败与连接失败，失败时不覆盖完整视图", async () => {
  const api = backend({ loadSurface: vi.fn().mockResolvedValue(makeSurface(1, 2)) });
  const explorer = useWorldExplorer(api);
  await explorer.initialize();
  await explorer.chooseWorld();
  expect(explorer.status.value).toEqual(appStatus);
  expect(explorer.phase.value).toBe("surface-partial");

  vi.mocked(api.buildMesh).mockResolvedValueOnce(makeMesh(1, 1));
  await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  expect(explorer.phase.value).toBe("mesh-partial");
  const completeMesh = explorer.mesh.value;

  vi.mocked(api.buildMesh).mockRejectedValueOnce({ code: "INVALID_CHUNK", message: "区块数据无效" });
  await explorer.selectRect({ minX: 1, minZ: 1, width: 1, depth: 1 });
  expect(explorer.phase.value).toBe("mesh-error");
  expect(explorer.businessError.value?.code).toBe("INVALID_CHUNK");
  expect(explorer.mesh.value).toBe(completeMesh);
  expect(explorer.statusError.value).toBeNull();
});

it("拒绝结构合法但不属于当前世界或选区的响应", async () => {
  const api = backend();
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();
  const oldSurface = explorer.surface.value;

  vi.mocked(api.selectWorld).mockResolvedValueOnce(makeWorld(2));
  vi.mocked(api.loadSurface).mockResolvedValueOnce(makeSurface(3));
  await explorer.chooseWorld();
  expect(explorer.phase.value).toBe("surface-error");
  expect(explorer.businessError.value?.code).toBe("INVALID_RESPONSE");
  expect(explorer.surface.value).toBe(oldSurface);
  expect(explorer.hasRetainedView.value).toBe(true);

  vi.mocked(api.selectWorld).mockResolvedValueOnce(makeWorld(1));
  vi.mocked(api.loadSurface).mockResolvedValueOnce(makeSurface(1));
  await explorer.chooseWorld();
  vi.mocked(api.buildMesh).mockResolvedValueOnce(makeMesh(1));
  await explorer.selectRect({ minX: 1, minZ: 1, width: 1, depth: 1 });
  expect(explorer.phase.value).toBe("mesh-error");
  expect(explorer.mesh.value).toBeNull();
  expect(explorer.businessError.value?.code).toBe("INVALID_RESPONSE");
});

it("关闭时进入 closing 并忽略选择器迟到结果", async () => {
  const selection = deferred<WorldSummary | null>();
  const api = backend({ selectWorld: vi.fn().mockReturnValue(selection.promise) });
  const explorer = useWorldExplorer(api);
  const request = explorer.chooseWorld();
  explorer.close();
  selection.resolve(makeWorld(2));
  await request;
  expect(explorer.phase.value).toBe("closing");
  expect(explorer.world.value).toBeNull();
  expect(api.loadSurface).not.toHaveBeenCalled();
  expect(api.cancelActiveTask).toHaveBeenCalledTimes(1);
});
