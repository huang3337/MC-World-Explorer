import { expect, it, vi } from "vitest";
import type { MeshData } from "../protocol/mesh-v1";
import type { AppStatus, DisplayError, WorldSummary } from "../services/types";
import { useWorldExplorer, type WorldExplorerBackend } from "./useWorldExplorer";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

const appStatus: AppStatus = { appVersion: "0.7.3", backendStatus: "ready", portablePaths: { root: "root", cache: "cache", logs: "logs", exports: "exports", config: "config" } };
function makeWorld(sessionId: number): WorldSummary { return { sessionId, name: `世界${sessionId}`, displayPath: `D:\\World${sessionId}`, dataVersion: null, centerX: 0, centerY: 64, centerZ: 0, centerSource: "worldSpawn", worldSpawn: { x: 0, y: 64, z: 0 }, loadAnchor: { x: 12, y: 70, z: -8 }, loadAnchorSource: "playerExit" }; }
function makeMesh(sessionId: number, warnings = 0): MeshData { return { positions: new Float32Array(), normals: new Float32Array(), indices: new Uint32Array(), batches: new Uint32Array(), metadata: { sessionId, taskId: sessionId, chunkRect: { minX: 0, minZ: 0, width: 1, depth: 1 }, originWorldX: 0, originWorldZ: 0, bounds: null, blockCount: 0, faceCount: 0, vertexCount: 0, indexCount: 0, batchCount: 0, warnings: warnings ? [{ kind: "targetUnavailable", chunkX: 0, chunkZ: 0, failure: "corrupt", blockName: null, occurrences: 1 }] : [] } }; }
function backend(overrides: Partial<WorldExplorerBackend> = {}): WorldExplorerBackend { return { getAppStatus: vi.fn().mockResolvedValue(appStatus), selectWorld: vi.fn().mockResolvedValue(makeWorld(1)), scanWorldDirectory: vi.fn().mockResolvedValue(null), openDiscoveredWorld: vi.fn().mockResolvedValue(makeWorld(1)), buildMesh: vi.fn().mockResolvedValue(makeMesh(1)), cancelActiveTask: vi.fn().mockResolvedValue(true), toDisplayError: reason => reason as DisplayError, ...overrides }; }

it("世界会话成功后立即提交地图，不再等待固定 surface", async () => {
  const api = backend(); const explorer = useWorldExplorer(api); await explorer.chooseWorld();
  expect(explorer.world.value?.sessionId).toBe(1); expect(explorer.phase.value).toBe("map-ready"); expect(explorer.canInteractWithMap.value).toBe(true); expect(explorer.mesh.value).toBeNull();
});

it("切换世界只等待会话选择，成功后立即替换地图并清除旧三维", async () => {
  const api = backend(); const explorer = useWorldExplorer(api); await explorer.chooseWorld(); await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 });
  const next = deferred<WorldSummary | null>(); vi.mocked(api.selectWorld).mockReturnValueOnce(next.promise); const switching = explorer.chooseWorld();
  expect(explorer.world.value?.sessionId).toBe(1); expect(explorer.hasRetainedView.value).toBe(true); expect(explorer.canInteractWithMap.value).toBe(false);
  next.resolve(makeWorld(2)); await switching;
  expect(explorer.world.value?.sessionId).toBe(2); expect(explorer.mesh.value).toBeNull(); expect(explorer.hasRetainedView.value).toBe(false); expect(explorer.canInteractWithMap.value).toBe(true);
});

it("扫描目录保留当前世界，列表选择只提交候选编号", async () => {
  const api = backend(); const explorer = useWorldExplorer(api); await explorer.chooseWorld(); const previous = explorer.world.value;
  const catalog = { rootPath: "D:\\MC", worlds: [{ id: 9, name: "世界2", group: "实例", displayPath: "D:\\MC\\世界2" }], skipped: 0 };
  vi.mocked(api.scanWorldDirectory).mockResolvedValueOnce(catalog); await explorer.scanDirectory(); expect(explorer.world.value).toBe(previous);
  vi.mocked(api.openDiscoveredWorld).mockResolvedValueOnce(makeWorld(2)); await explorer.chooseWorld(9); expect(api.openDiscoveredWorld).toHaveBeenCalledWith(9); expect(explorer.world.value?.sessionId).toBe(2);
});

it("缺失和视觉回退不把三维升级为部分失败", async () => {
  const api = backend(); const explorer = useWorldExplorer(api); await explorer.chooseWorld();
  const missing = makeMesh(1, 1); missing.metadata.warnings[0].failure = "missing"; vi.mocked(api.buildMesh).mockResolvedValueOnce(missing); await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 }); expect(explorer.phase.value).toBe("mesh-ready");
  const fallback = makeMesh(1, 1); fallback.metadata.warnings[0] = { kind: "fallbackBlock", chunkX: 0, chunkZ: 0, failure: null, blockName: "example:block", occurrences: 1 }; vi.mocked(api.buildMesh).mockResolvedValueOnce(fallback); await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 }); expect(explorer.phase.value).toBe("mesh-ready");
});

it("生成期间保存实际选区，取消后清除并忽略迟到结果", async () => {
  const pending = deferred<MeshData>();
  const api = backend({ buildMesh: vi.fn().mockReturnValue(pending.promise) });
  const explorer = useWorldExplorer(api);
  await explorer.chooseWorld();
  const rect = { minX: 4, minZ: -7, width: 3, depth: 3 };
  const request = explorer.selectRect(rect);
  expect(explorer.activeMeshRect.value).toEqual(rect);
  await explorer.cancel();
  expect(explorer.activeMeshRect.value).toBeNull();
  pending.resolve(makeMesh(1));
  await request;
  expect(explorer.activeMeshRect.value).toBeNull();
});

it("取消三维后忽略迟到结果并保留此前完整三维", async () => {
  const api = backend(); const explorer = useWorldExplorer(api); await explorer.chooseWorld(); await explorer.selectRect({ minX: 0, minZ: 0, width: 1, depth: 1 }); const oldMesh = explorer.mesh.value;
  const late = deferred<MeshData>(); vi.mocked(api.buildMesh).mockReturnValueOnce(late.promise); const request = explorer.selectRect({ minX: 1, minZ: 1, width: 1, depth: 1 }); await explorer.cancel(); late.resolve(makeMesh(1, 1)); await request;
  expect(explorer.phase.value).toBe("mesh-cancelled"); expect(explorer.mesh.value).toBe(oldMesh); expect(api.cancelActiveTask).toHaveBeenCalledTimes(1);
});

it("关闭后忽略选择器迟到结果", async () => {
  const selection = deferred<WorldSummary | null>(); const api = backend({ selectWorld: vi.fn().mockReturnValue(selection.promise) }); const explorer = useWorldExplorer(api); const request = explorer.chooseWorld(); explorer.close(); selection.resolve(makeWorld(2)); await request;
  expect(explorer.phase.value).toBe("closing"); expect(explorer.world.value).toBeNull(); expect(api.cancelActiveTask).toHaveBeenCalledTimes(1);
});
