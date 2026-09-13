import { computed, ref } from "vue";
import {
  buildMesh,
  cancelActiveTask,
  getAppStatus,
  loadSurface,
  selectWorld,
  scanWorldDirectory,
  openDiscoveredWorld,
  toDisplayError,
} from "../services/backend";
import type { MeshData } from "../protocol/mesh-v1";
import type { SurfaceData } from "../protocol/surface-v1";
import type { AppStatus, ChunkRect, DisplayError, WorldSummary, WorldScan } from "../services/types";

export type ExplorerPhase =
  | "idle"
  | "selecting"
  | "selection-cancelled"
  | "selection-error"
  | "surface-loading"
  | "surface-ready"
  | "surface-partial"
  | "surface-cancelled"
  | "surface-error"
  | "mesh-loading"
  | "mesh-ready"
  | "mesh-partial"
  | "mesh-cancelled"
  | "mesh-error"
  | "closing";

type ActiveOperation = "select" | "surface" | "mesh";

export interface WorldExplorerBackend {
  getAppStatus: () => Promise<AppStatus>;
  selectWorld: () => Promise<WorldSummary | null>;
  scanWorldDirectory: () => Promise<WorldScan | null>;
  openDiscoveredWorld: (id: number) => Promise<WorldSummary | null>;
  loadSurface: (sessionId: number) => Promise<SurfaceData>;
  buildMesh: (sessionId: number, rect: ChunkRect) => Promise<MeshData>;
  cancelActiveTask: () => Promise<boolean>;
  toDisplayError: (reason: unknown) => DisplayError;
}

const defaultBackend: WorldExplorerBackend = {
  getAppStatus,
  selectWorld,
  scanWorldDirectory,
  openDiscoveredWorld,
  loadSurface,
  buildMesh,
  cancelActiveTask,
  toDisplayError,
};

function cancelledPhase(operation: ActiveOperation): ExplorerPhase {
  if (operation === "surface") return "surface-cancelled";
  if (operation === "mesh") return "mesh-cancelled";
  return "selection-cancelled";
}

function failedPhase(operation: ActiveOperation): ExplorerPhase {
  if (operation === "surface") return "surface-error";
  if (operation === "mesh") return "mesh-error";
  return "selection-error";
}

function isCancellation(error: DisplayError): boolean {
  return error.code === "TASK_CANCELLED"
    || error.code === "TASK_OUTDATED"
    || error.code === "SESSION_OUTDATED";
}

function surfaceBelongsToWorld(surface: SurfaceData, world: WorldSummary): boolean {
  const surfaceSpawn = surface.metadata.worldSpawn;
  const worldSpawn = world.worldSpawn;
  return surface.metadata.sessionId === world.sessionId
    && surface.metadata.centerX === world.centerX
    && surface.metadata.centerY === world.centerY
    && surface.metadata.centerZ === world.centerZ
    && surface.metadata.centerSource === world.centerSource
    && (surfaceSpawn === null) === (worldSpawn === null)
    && (surfaceSpawn === null || worldSpawn === null || (
      surfaceSpawn.x === worldSpawn.x
      && surfaceSpawn.y === worldSpawn.y
      && surfaceSpawn.z === worldSpawn.z
    ));
}

function meshBelongsToRequest(mesh: MeshData, sessionId: number, rect: ChunkRect): boolean {
  const actual = mesh.metadata.chunkRect;
  return mesh.metadata.sessionId === sessionId
    && actual.minX === rect.minX
    && actual.minZ === rect.minZ
    && actual.width === rect.width
    && actual.depth === rect.depth;
}

export function useWorldExplorer(backend: WorldExplorerBackend = defaultBackend) {
  const status = ref<AppStatus | null>(null);
  const statusError = ref<DisplayError | null>(null);
  const businessError = ref<DisplayError | null>(null);
  const phase = ref<ExplorerPhase>("idle");
  const operation = ref<ActiveOperation | null>(null);
  const world = ref<WorldSummary | null>(null);
  const library = ref<WorldScan | null>(null);
  const scanning = ref(false);
  const pendingWorld = ref<WorldSummary | null>(null);
  const surface = ref<SurfaceData | null>(null);
  const mesh = ref<MeshData | null>(null);
  const activeSessionId = ref<number | null>(null);
  let generation = 0;
  let disposed = false;

  const busy = computed(() => operation.value !== null);
  const cancellable = computed(() => operation.value === "surface" || operation.value === "mesh");
  const hasRetainedView = computed(() => surface.value !== null && (
    operation.value === "select"
      || operation.value === "surface"
      || (activeSessionId.value !== null && world.value?.sessionId !== activeSessionId.value)
  ));
  const canInteractWithSurface = computed(() => (
    surface.value !== null
      && operation.value === null
      && world.value?.sessionId === activeSessionId.value
      && phase.value !== "closing"
  ));

  async function initialize(): Promise<void> {
    try {
      const result = await backend.getAppStatus();
      if (!disposed) status.value = result;
    } catch (reason) {
      if (!disposed) statusError.value = backend.toDisplayError(reason);
    }
  }

  async function scanDirectory(): Promise<void> {
    if (busy.value || disposed) return;
    const current = ++generation;
    const previousPhase = phase.value;
    operation.value = "select";
    scanning.value = true;
    businessError.value = null;
    try {
      const result = await backend.scanWorldDirectory();
      if (disposed || current !== generation) return;
      if (result) library.value = result;
      phase.value = previousPhase;
    } catch (reason) {
      if (disposed || current !== generation) return;
      businessError.value = backend.toDisplayError(reason);
      phase.value = "selection-error";
    } finally {
      if (!disposed && current === generation) {
        operation.value = null;
        scanning.value = false;
      }
    }
  }

  async function chooseWorld(worldId?: number): Promise<void> {
    if (busy.value || disposed) return;
    const current = ++generation;
    operation.value = "select";
    phase.value = "selecting";
    pendingWorld.value = null;
    businessError.value = null;
    try {
      const selected = worldId === undefined
        ? await backend.selectWorld() : await backend.openDiscoveredWorld(worldId);
      if (disposed || current !== generation) return;
      if (!selected) {
        phase.value = "selection-cancelled";
        return;
      }

      pendingWorld.value = selected;
      activeSessionId.value = selected.sessionId;
      operation.value = "surface";
      phase.value = "surface-loading";
      const nextSurface = await backend.loadSurface(selected.sessionId);
      if (disposed || current !== generation) return;
      if (!surfaceBelongsToWorld(nextSurface, selected)) {
        throw backend.toDisplayError({ code: "INVALID_RESPONSE" });
      }

      // 新世界的二维数据完整解码后一次性提交，并同时丢弃旧选区对应的三维。
      world.value = selected;
      surface.value = nextSurface;
      mesh.value = null;
      pendingWorld.value = null;
      phase.value = nextSurface.metadata.failedChunks > 0 ? "surface-partial" : "surface-ready";
    } catch (reason) {
      if (disposed || current !== generation) return;
      const error = backend.toDisplayError(reason);
      if (isCancellation(error)) {
        phase.value = cancelledPhase(operation.value ?? "select");
      } else {
        businessError.value = error;
        phase.value = failedPhase(operation.value ?? "select");
      }
    } finally {
      if (!disposed && current === generation) operation.value = null;
    }
  }

  async function selectRect(rect: ChunkRect): Promise<void> {
    const selectedWorld = world.value;
    if (!selectedWorld || !canInteractWithSurface.value) return;
    const requestedRect = { ...rect };
    const current = ++generation;
    operation.value = "mesh";
    phase.value = "mesh-loading";
    businessError.value = null;
    try {
      const nextMesh = await backend.buildMesh(selectedWorld.sessionId, requestedRect);
      if (disposed || current !== generation) return;
      if (!meshBelongsToRequest(nextMesh, selectedWorld.sessionId, requestedRect)) {
        throw backend.toDisplayError({ code: "INVALID_RESPONSE" });
      }
      mesh.value = nextMesh;
      phase.value = nextMesh.metadata.warnings.some((warning) => (
        warning.kind === "targetUnavailable" && warning.failure !== "missing"
      )) ? "mesh-partial" : "mesh-ready";
    } catch (reason) {
      if (disposed || current !== generation) return;
      const error = backend.toDisplayError(reason);
      if (isCancellation(error)) {
        phase.value = "mesh-cancelled";
      } else {
        businessError.value = error;
        phase.value = "mesh-error";
      }
    } finally {
      if (!disposed && current === generation) operation.value = null;
    }
  }

  async function cancel(): Promise<void> {
    const currentOperation = operation.value;
    if (!currentOperation) return;
    const current = ++generation;
    operation.value = null;
    phase.value = cancelledPhase(currentOperation);
    businessError.value = null;
    try {
      await backend.cancelActiveTask();
    } catch (reason) {
      if (disposed || current !== generation) return;
      businessError.value = backend.toDisplayError(reason);
      phase.value = failedPhase(currentOperation);
    }
  }

  function close(): void {
    if (disposed) return;
    disposed = true;
    generation += 1;
    operation.value = null;
    phase.value = "closing";
    void backend.cancelActiveTask().catch(() => undefined);
  }

  return {
    status,
    statusError,
    businessError,
    phase,
    operation,
    busy,
    cancellable,
    world,
    library,
    scanning,
    scanDirectory,
    pendingWorld,
    surface,
    mesh,
    hasRetainedView,
    canInteractWithSurface,
    initialize,
    chooseWorld,
    selectRect,
    cancel,
    close,
  };
}
