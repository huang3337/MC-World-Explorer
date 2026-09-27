<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import { MAP_TILE_PIXELS, type MapTileZoom } from "../protocol/map-tile-v1";
import type { MapBatchItem } from "../protocol/map-batch-v1";
import type { ChunkRect, DisplayError, MapViewportTarget, WorldSummary } from "../services/types";
import { cancelMapViewport, nextMapViewportBatch, startMapViewport } from "../services/backend";
import { DISPLAY_ZOOMS, displayZoomLabel, isTemporaryScale, nearestDisplayZoom, tileZoom, visibleTiles, wheelVisualZoom, zoomAt, type DisplayZoom, type ViewportState } from "../composables/mapViewportMath";
import { summarizeMapViewport, type MapViewportCompletion, type MapViewportProgress } from "../composables/mapViewportCompletion";
import { trimViewportVisuals } from "../composables/mapViewportCache";

const props = withDefaults(defineProps<{ world: WorldSummary; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ select: [rect: ChunkRect] }>();
const canvas = ref<HTMLCanvasElement>();
const preset = ref(3), locateX = ref(0), locateZ = ref(0);
const view = ref<ViewportState>({ centerX: 0, centerZ: 0, blocksPerPixel: 2 });
const committedZoom = ref<MapTileZoom>(2);
const committedDisplayZoom = ref(2);
const selection = ref<ChunkRect>({ minX: 0, minZ: 0, width: 3, depth: 3 });
const hover = ref<{ x: number; z: number } | null>(null);
const targetCount = ref(0), terminalCount = ref(0), readyCount = ref(0), failedCount = ref(0);
const successfulChunks = ref(0), missingChunks = ref(0), unsupportedChunks = ref(0), corruptChunks = ref(0);
const loadError = ref<DisplayError | null>(null);
const stopped = ref<MapViewportCompletion | null>(null);

function recordInterruption(code: DisplayError["code"]): boolean {
  switch (code) {
    case "TASK_CANCELLED":
      stopped.value = { kind: "cancelled", text: "当前视口加载已取消，可重试", retryable: true }; break;
    case "TASK_OUTDATED":
      stopped.value = { kind: "outdated", text: "当前视口加载任务已过期，可重试", retryable: true }; break;
    case "SESSION_OUTDATED":
      stopped.value = { kind: "session-outdated", text: "世界会话已过期，请重新选择世界", retryable: false }; break;
    case "SHUTTING_DOWN":
      stopped.value = { kind: "shutting-down", text: "应用正在关闭，地图加载已停止", retryable: false }; break;
    default: return false;
  }
  return true;
}

type TileState = "partial" | "ready" | "failed";
type ChunkCounts = Pick<Extract<MapBatchItem, { kind: "ready" }>, "successfulChunks" | "missingChunks" | "unsupportedChunks" | "corruptChunks">;
interface TileVisual {
  tileX: number; tileZ: number; blocksPerPixel: MapTileZoom; state: TileState; revision: number;
  colors: Uint32Array; statuses: Uint8Array; image: HTMLCanvasElement | null; chunkCounts?: ChunkCounts;
}

const tiles = shallowRef(new Map<string, TileVisual>());
const viewportSize = ref({ width: 800, height: 520 });
let resizeObserver: ResizeObserver | null = null;
let generation = 0, requestTimer: number | null = null, zoomTimer: number | null = null;
let zoomAnimation: number | null = null;
let requestedTargets = new Set<string>();
let requestedCoverage = new Set<string>();
let activeStates = new Map<string, "queued" | TileState>();
let previousZoom: MapTileZoom | null = null;
let pointerId: number | null = null, pressX = 0, pressY = 0, lastX = 0, lastY = 0, dragging = false;
let disposed = false;
let currentViewport: { sessionId: number; viewportTaskId: number } | null = null;

function releaseViewport(sessionId: number, viewportTaskId: number) {
  void cancelMapViewport(sessionId, viewportTaskId).catch(error => {
    console.warn("地图视口取消失败", error);
  });
}

const keyOf = (x: number, z: number, bpp: number) => `${bpp}:${x}:${z}`;
function size() { const rect = canvas.value?.getBoundingClientRect(); return { width: Math.max(1, Math.round(rect?.width || 800)), height: Math.max(1, Math.round(rect?.height || 520)) }; }
function worldAt(clientX: number, clientY: number) {
  const rect = canvas.value?.getBoundingClientRect(); if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return { x: view.value.centerX + (clientX - rect.left - rect.width / 2) * view.value.blocksPerPixel, z: view.value.centerZ + (clientY - rect.top - rect.height / 2) * view.value.blocksPerPixel };
}
function screen(x: number, z: number, width: number, height: number): [number, number] { return [width / 2 + (x - view.value.centerX) / view.value.blocksPerPixel, height / 2 + (z - view.value.centerZ) / view.value.blocksPerPixel]; }
function imageFor(colors: Uint32Array, statuses: Uint8Array, transparentUnloaded = false): HTMLCanvasElement {
  const target = document.createElement("canvas"); target.width = MAP_TILE_PIXELS; target.height = MAP_TILE_PIXELS;
  const context = target.getContext("2d")!; const image = context.createImageData(MAP_TILE_PIXELS, MAP_TILE_PIXELS); const statusColors = [0, 0x20262b, 0x785f24, 0x7f2633, 0x151a1e];
  for (let i = 0; i < colors.length; i += 1) { const status = statuses[i], rgb = status === 0 ? colors[i] : statusColors[status]; image.data[i * 4] = rgb >> 16 & 255; image.data[i * 4 + 1] = rgb >> 8 & 255; image.data[i * 4 + 2] = rgb & 255; image.data[i * 4 + 3] = transparentUnloaded && status === 4 ? 0 : 255; }
  context.putImageData(image, 0, 0); return target;
}
function drawTile(context: CanvasRenderingContext2D, tile: TileVisual, width: number, height: number) {
  const tileSize = MAP_TILE_PIXELS * tile.blocksPerPixel, [x, z] = screen(tile.tileX * tileSize, tile.tileZ * tileSize, width, height), display = tileSize / view.value.blocksPerPixel;
  if (tile.image) context.drawImage(tile.image, Math.floor(x), Math.floor(z), Math.ceil(display), Math.ceil(display));
  if (tile.state === "failed") { context.fillStyle = "rgba(127,38,51,.72)"; context.fillRect(Math.floor(x), Math.floor(z), Math.ceil(display), Math.ceil(display)); context.strokeStyle = "#fda4af"; context.lineWidth = 2; context.strokeRect(Math.floor(x), Math.floor(z), Math.ceil(display), Math.ceil(display)); }
}
function draw() {
  const target = canvas.value; if (!target) return; const { width, height } = size(), dpr = Math.min(2, Math.max(1, globalThis.devicePixelRatio || 1));
  if (viewportSize.value.width !== width || viewportSize.value.height !== height) viewportSize.value = { width, height };
  if (target.width !== Math.round(width * dpr)) target.width = Math.round(width * dpr); if (target.height !== Math.round(height * dpr)) target.height = Math.round(height * dpr);
  const context = target.getContext("2d"); if (!context) return; context.setTransform(dpr, 0, 0, dpr, 0, 0); context.imageSmoothingEnabled = isTemporaryScale(view.value.blocksPerPixel, committedDisplayZoom.value); context.fillStyle = "#151a1e"; context.fillRect(0, 0, width, height);
  if (previousZoom !== null && previousZoom !== committedZoom.value) for (const tile of tiles.value.values()) if (tile.blocksPerPixel === previousZoom && tile.state === "ready") drawTile(context, tile, width, height);
  for (const tile of tiles.value.values()) if (tile.blocksPerPixel === committedZoom.value) drawTile(context, tile, width, height);
  if (view.value.blocksPerPixel <= 4) { const minX = view.value.centerX - width * view.value.blocksPerPixel / 2, maxX = view.value.centerX + width * view.value.blocksPerPixel / 2, minZ = view.value.centerZ - height * view.value.blocksPerPixel / 2, maxZ = view.value.centerZ + height * view.value.blocksPerPixel / 2; context.beginPath(); for (let x = Math.floor(minX / 16) * 16; x <= maxX; x += 16) { const sx = screen(x, 0, width, height)[0]; context.moveTo(sx, 0); context.lineTo(sx, height); } for (let z = Math.floor(minZ / 16) * 16; z <= maxZ; z += 16) { const sz = screen(0, z, width, height)[1]; context.moveTo(0, sz); context.lineTo(width, sz); } context.strokeStyle = "rgba(235,244,250,.28)"; context.lineWidth = 1; context.stroke(); }
  const cross = (x: number, z: number, color: string, radius: number) => { const [sx, sz] = screen(x + .5, z + .5, width, height); context.beginPath(); context.moveTo(sx - radius, sz); context.lineTo(sx + radius, sz); context.moveTo(sx, sz - radius); context.lineTo(sx, sz + radius); context.strokeStyle = color; context.lineWidth = 2; context.stroke(); };
  cross(props.world.loadAnchor.x, props.world.loadAnchor.z, "#fff", 7); const spawn = props.world.worldSpawn; if (spawn) cross(spawn.x, spawn.z, "#fbbf24", 5);
  const [sx, sz] = screen(selection.value.minX * 16, selection.value.minZ * 16, width, height), sw = selection.value.width * 16 / view.value.blocksPerPixel, sh = selection.value.depth * 16 / view.value.blocksPerPixel; context.fillStyle = "rgba(79,155,200,.22)"; context.fillRect(sx, sz, sw, sh); context.strokeStyle = "#7dd3fc"; context.lineWidth = 2; context.strokeRect(sx, sz, sw, sh);
}
function sameRequestedTargets() {
  if (requestedCoverage.size === 0) return false;
  const targets = visibleTiles(view.value, viewportSize.value.width, viewportSize.value.height, committedZoom.value);
  return targets.every(tile => requestedCoverage.has(keyOf(tile.tileX, tile.tileZ, tile.blocksPerPixel)));
}
function scheduleTiles(delay = 80, force = false) {
  if (disposed || (stopped.value && (!stopped.value.retryable || !force))) return;
  if (force) stopped.value = null;
  if (!force && sameRequestedTargets() && loadError.value === null && stopped.value === null) return;
  if (force && requestTimer !== null) { clearTimeout(requestTimer); requestTimer = null; }
  if (requestTimer !== null) return;
  requestTimer = globalThis.setTimeout(() => { requestTimer = null; void loadVisible(); }, delay);
}
function stopZoomAnimation() { if (zoomAnimation !== null) cancelAnimationFrame(zoomAnimation); zoomAnimation = null; }
function currentViewNeedsTiles(width: number, height: number) {
  return visibleTiles(view.value, width, height, committedZoom.value).some(tile => {
    const key = keyOf(tile.tileX, tile.tileZ, tile.blocksPerPixel), visual = tiles.value.get(key);
    return !requestedTargets.has(key) && visual?.state !== "ready";
  });
}
function settleZoom(target: DisplayZoom, pointerX: number, pointerY: number, width: number, height: number) {
  stopZoomAnimation();
  const start = view.value.blocksPerPixel, nextZoom = tileZoom(target);
  if (nextZoom !== committedZoom.value) previousZoom = committedZoom.value;
  committedZoom.value = nextZoom; committedDisplayZoom.value = target;
  if (Math.abs(start - target) <= .000_001) { draw(); scheduleTiles(0); return; }
  const started = performance.now(), startLog = Math.log(start), endLog = Math.log(target);
  const frame = (now: number) => {
    const progress = Math.min(1, Math.max(0, (now - started) / 120)), eased = 1 - Math.pow(1 - progress, 3);
    const next = Math.exp(startLog + (endLog - startLog) * eased);
    view.value = zoomAt(view.value, next, pointerX, pointerY, width, height); draw();
    if (progress < 1) zoomAnimation = requestAnimationFrame(frame);
    else { zoomAnimation = null; scheduleTiles(0); }
  };
  zoomAnimation = requestAnimationFrame(frame);
}
function ensurePartial(item: Extract<MapBatchItem, { kind: "partial" }>, current: "queued" | TileState): TileVisual {
  const key = keyOf(item.tileX, item.tileZ, item.blocksPerPixel), existing = tiles.value.get(key);
  if (current === "partial" && existing) return existing;
  const preserve = existing?.state === "ready" || existing?.state === "partial";
  return { tileX: item.tileX, tileZ: item.tileZ, blocksPerPixel: item.blocksPerPixel, state: "partial", revision: 0, colors: preserve ? new Uint32Array(existing.colors) : new Uint32Array(MAP_TILE_PIXELS ** 2), statuses: preserve ? new Uint8Array(existing.statuses) : new Uint8Array(MAP_TILE_PIXELS ** 2).fill(4), image: null };
}
function commitItem(item: MapBatchItem, targets: Set<string>, states: Map<string, "queued" | TileState>, revisions: Map<string, number>) {
  const key = keyOf(item.tileX, item.tileZ, item.blocksPerPixel); if (!targets.has(key)) throw new Error("INVALID_RESPONSE"); const next = new Map(tiles.value);
  if (item.kind === "partial") { const current = states.get(key) ?? "queued"; if (current === "ready" || current === "failed" || item.revision <= (revisions.get(key) ?? 0)) throw new Error("INVALID_RESPONSE"); revisions.set(key, item.revision); states.set(key, "partial"); if (next.get(key)?.state === "ready") return; const tile = ensurePartial(item, current); for (let row = 0; row < item.height; row += 1) { const source = row * item.width, destination = (item.pixelZ + row) * MAP_TILE_PIXELS + item.pixelX; tile.colors.set(item.colors.subarray(source, source + item.width), destination); tile.statuses.set(item.statuses.subarray(source, source + item.width), destination); } tile.revision = item.revision; tile.state = "partial"; tile.image = imageFor(tile.colors, tile.statuses, true); next.set(key, tile); }
  else if (item.kind === "ready") { if (["ready", "failed"].includes(states.get(key) || "")) throw new Error("INVALID_RESPONSE"); const colors = new Uint32Array(item.colors), statuses = new Uint8Array(item.statuses); next.set(key, { tileX: item.tileX, tileZ: item.tileZ, blocksPerPixel: item.blocksPerPixel, state: "ready", revision: Number.MAX_SAFE_INTEGER, colors, statuses, image: imageFor(colors, statuses), chunkCounts: { successfulChunks: item.successfulChunks, missingChunks: item.missingChunks, unsupportedChunks: item.unsupportedChunks, corruptChunks: item.corruptChunks } }); states.set(key, "ready"); terminalCount.value += 1; readyCount.value += 1; successfulChunks.value += item.successfulChunks; missingChunks.value += item.missingChunks; unsupportedChunks.value += item.unsupportedChunks; corruptChunks.value += item.corruptChunks; }
  else { if (["ready", "failed"].includes(states.get(key) || "")) throw new Error("INVALID_RESPONSE"); next.set(key, { tileX: item.tileX, tileZ: item.tileZ, blocksPerPixel: item.blocksPerPixel, state: "failed", revision: Number.MAX_SAFE_INTEGER, colors: new Uint32Array(0), statuses: new Uint8Array(0), image: null }); states.set(key, "failed"); terminalCount.value += 1; failedCount.value += 1; }
  trimViewportVisuals(next, requestedTargets, 128); tiles.value = next; draw();
}
async function loadVisible() {
  if (disposed || stopped.value) return;
  const own = ++generation, dimensions = size();
  let startedViewport: { sessionId: number; viewportTaskId: number } | null = null;
  const coverage = new Set(visibleTiles(view.value, dimensions.width, dimensions.height, committedZoom.value).map(tile => keyOf(tile.tileX, tile.tileZ, tile.blocksPerPixel)));
  activeStates = new Map();
  targetCount.value = 0; terminalCount.value = 0; readyCount.value = 0; failedCount.value = 0; successfulChunks.value = 0; missingChunks.value = 0; unsupportedChunks.value = 0; corruptChunks.value = 0; loadError.value = null;
  stopped.value = null;
  try {
    const summary = await startMapViewport(props.world.sessionId, view.value.centerX, view.value.centerZ, dimensions.width, dimensions.height, committedZoom.value, view.value.blocksPerPixel); if (own !== generation) { releaseViewport(summary.sessionId, summary.viewportTaskId); return; }
    startedViewport = { sessionId: summary.sessionId, viewportTaskId: summary.viewportTaskId };
    currentViewport = startedViewport;
    const targets = new Set(summary.targets.map((target: MapViewportTarget) => keyOf(target.tileX, target.tileZ, target.blocksPerPixel)));
    if (targets.size !== coverage.size || Array.from(coverage).some(key => !targets.has(key))) throw new Error("INVALID_RESPONSE");
    const states = new Map<string, "queued" | TileState>(Array.from(targets, key => [key, "queued"])); const revisions = new Map<string, number>(); activeStates = states; requestedTargets = new Set(targets); requestedCoverage = coverage; targetCount.value = summary.targetCount; let expectedSequence = 1;
    while (own === generation) { const batch = await nextMapViewportBatch(props.world.sessionId, summary.viewportTaskId); if (own !== generation) return; if (batch.metadata.batchSequence !== expectedSequence) throw new Error("INVALID_RESPONSE"); expectedSequence += 1; for (const item of batch.items) { if (own !== generation) return; commitItem(item, targets, states, revisions); } if (batch.metadata.state === "ended") { if (terminalCount.value !== targetCount.value || Array.from(states).some(([key, state]) => state !== "failed" && (state !== "ready" || tiles.value.get(key)?.state !== "ready"))) throw new Error("INVALID_RESPONSE"); return; } if (batch.metadata.state === "cancelled") throw { code: "TASK_CANCELLED" }; if (batch.metadata.state === "shuttingDown") throw { code: "SHUTTING_DOWN" }; }
  } catch (reason) {
    if (startedViewport) {
      releaseViewport(startedViewport.sessionId, startedViewport.viewportTaskId);
      if (currentViewport === startedViewport) currentViewport = null;
    }
    if (own !== generation) return;
    if (!startedViewport && currentViewport) {
      releaseViewport(currentViewport.sessionId, currentViewport.viewportTaskId);
      currentViewport = null;
    }
    const error = reason instanceof Error && reason.message === "INVALID_RESPONSE" ? { code: "INVALID_RESPONSE", message: "后端返回的数据无效，请检查前后端版本" } as DisplayError : reason as DisplayError;
    requestedCoverage = coverage;
    if (!recordInterruption(error.code)) loadError.value = error;
  }
}
function place(worldX: number, worldZ: number) { const side = preset.value, cx = Math.floor(worldX / 16), cz = Math.floor(worldZ / 16); selection.value = { minX: cx - Math.floor((side - 1) / 2), minZ: cz - Math.floor((side - 1) / 2), width: side, depth: side }; draw(); }
function reset() { generation += 1; stopZoomAnimation(); requestedTargets = new Set(); requestedCoverage = new Set(); activeStates = new Map(); tiles.value = new Map(); previousZoom = null; view.value = { centerX: props.world.loadAnchor.x, centerZ: props.world.loadAnchor.z, blocksPerPixel: 2 }; committedZoom.value = 2; committedDisplayZoom.value = 2; preset.value = 3; place(view.value.centerX, view.value.centerZ); nextTick(() => { draw(); scheduleTiles(0); }); }
function pointerDown(event: PointerEvent) { if (props.disabled || event.button !== 0 || pointerId !== null) return; pointerId = event.pointerId; pressX = lastX = event.clientX; pressY = lastY = event.clientY; dragging = false; canvas.value?.setPointerCapture?.(event.pointerId); canvas.value?.focus({ preventScroll: true }); event.preventDefault(); }
function pointerMove(event: PointerEvent) { hover.value = worldAt(event.clientX, event.clientY); if (pointerId !== event.pointerId) { draw(); return; } if (!dragging && Math.hypot(event.clientX - pressX, event.clientY - pressY) <= 4) return; dragging = true; view.value = { ...view.value, centerX: view.value.centerX - (event.clientX - lastX) * view.value.blocksPerPixel, centerZ: view.value.centerZ - (event.clientY - lastY) * view.value.blocksPerPixel }; lastX = event.clientX; lastY = event.clientY; draw(); scheduleTiles(); event.preventDefault(); }
function pointerUp(event: PointerEvent) { if (pointerId !== event.pointerId) return; if (!dragging) { const point = worldAt(event.clientX, event.clientY); if (point) place(point.x, point.z); } pointerId = null; dragging = false; if (canvas.value?.hasPointerCapture?.(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId); }
function wheel(event: WheelEvent) { if (props.disabled) return; stopZoomAnimation(); const rect = canvas.value!.getBoundingClientRect(), pointerX = event.clientX - rect.left, pointerY = event.clientY - rect.top, next = wheelVisualZoom(view.value.blocksPerPixel, event.deltaY); view.value = zoomAt(view.value, next, pointerX, pointerY, rect.width, rect.height); draw(); event.preventDefault(); if (zoomTimer !== null) clearTimeout(zoomTimer); zoomTimer = globalThis.setTimeout(() => { zoomTimer = null; const target = nearestDisplayZoom(view.value.blocksPerPixel), nextZoom = tileZoom(target); if (nextZoom === committedZoom.value) { committedDisplayZoom.value = view.value.blocksPerPixel; draw(); if (currentViewNeedsTiles(rect.width, rect.height)) scheduleTiles(0); } else settleZoom(target, pointerX, pointerY, rect.width, rect.height); }, 150); }
function stepZoom(direction: -1 | 1) { if (zoomTimer !== null) { clearTimeout(zoomTimer); zoomTimer = null; } const current = nearestDisplayZoom(view.value.blocksPerPixel), index = DISPLAY_ZOOMS.indexOf(current), next = DISPLAY_ZOOMS[Math.max(0, Math.min(DISPLAY_ZOOMS.length - 1, index + direction))], dimensions = size(); settleZoom(next, dimensions.width / 2, dimensions.height / 2, dimensions.width, dimensions.height); }
function locate() { view.value = { ...view.value, centerX: locateX.value, centerZ: locateZ.value }; draw(); scheduleTiles(0); }
function keyDown(event: KeyboardEvent) { const moves: Record<string, [number, number]> = { ArrowLeft: [-1,0], ArrowRight: [1,0], ArrowUp: [0,-1], ArrowDown: [0,1] }, move = moves[event.key]; if (move && !props.disabled) { view.value = { ...view.value, centerX: view.value.centerX + move[0] * 16, centerZ: view.value.centerZ + move[1] * 16 }; draw(); scheduleTiles(); event.preventDefault(); } }
function visibleProgress(currentTargetCount: number): MapViewportProgress {
  const visible = visibleTiles(view.value, viewportSize.value.width, viewportSize.value.height, committedZoom.value);
  if (visible.length === currentTargetCount) return { targetCount: currentTargetCount, terminalCount: terminalCount.value, readyCount: readyCount.value, failedCount: failedCount.value, successfulChunks: successfulChunks.value, missingChunks: missingChunks.value, unsupportedChunks: unsupportedChunks.value, corruptChunks: corruptChunks.value };
  const progress: MapViewportProgress = { targetCount: visible.length, terminalCount: 0, readyCount: 0, failedCount: 0, successfulChunks: 0, missingChunks: 0, unsupportedChunks: 0, corruptChunks: 0 };
  for (const tile of visible) {
    const key = keyOf(tile.tileX, tile.tileZ, tile.blocksPerPixel);
    const state = activeStates.get(key);
    if (state === "failed") { progress.terminalCount += 1; progress.failedCount += 1; }
    else if (state === "ready") {
      const visual = tiles.value.get(key);
      if (visual?.state !== "ready" || !visual.chunkCounts) continue;
      progress.terminalCount += 1; progress.readyCount += 1;
      progress.successfulChunks += visual.chunkCounts.successfulChunks;
      progress.missingChunks += visual.chunkCounts.missingChunks;
      progress.unsupportedChunks += visual.chunkCounts.unsupportedChunks;
      progress.corruptChunks += visual.chunkCounts.corruptChunks;
    }
  }
  return progress;
}
const hoverText = computed(() => hover.value ? `世界 X ${Math.floor(hover.value.x)}，Z ${Math.floor(hover.value.z)}` : "悬停：无"), zoomText = computed(() => displayZoomLabel(committedDisplayZoom.value, committedZoom.value)), completion = computed<MapViewportCompletion>(() => {
  const currentTargetCount = targetCount.value, currentError = loadError.value;
  if (stopped.value) return stopped.value;
  if (!sameRequestedTargets()) return { kind: "loading" as const, text: "正在建立当前视口…", retryable: false };
  if (currentError) return { kind: "request-failed", text: "当前视口请求失败，可重试", retryable: true };
  return summarizeMapViewport(visibleProgress(currentTargetCount));
});
watch(() => props.world, () => { if (stopped.value?.kind !== "shutting-down") stopped.value = null; reset(); }, { immediate: true }); watch(() => props.disabled, disabled => { if (disabled) { pointerId = null; dragging = false; } });
onMounted(() => { if (typeof ResizeObserver !== "undefined" && canvas.value?.parentElement) { resizeObserver = new ResizeObserver(() => { draw(); scheduleTiles(); }); resizeObserver.observe(canvas.value.parentElement); } draw(); scheduleTiles(0); });
onBeforeUnmount(() => { disposed = true; generation += 1; if (currentViewport) releaseViewport(currentViewport.sessionId, currentViewport.viewportTaskId); stopZoomAnimation(); resizeObserver?.disconnect(); if (requestTimer !== null) clearTimeout(requestTimer); if (zoomTimer !== null) clearTimeout(zoomTimer); tiles.value.clear(); });
</script>

<template>
  <figure class="surface-map" :class="{ 'is-disabled': disabled }" :aria-disabled="disabled">
    <div class="map-toolbar"><label>X <input v-model.number="locateX" type="number" :disabled="disabled" @keyup.enter="locate"></label><label>Z <input v-model.number="locateZ" type="number" :disabled="disabled" @keyup.enter="locate"></label><button type="button" :disabled="disabled" @click="locate">定位</button><button type="button" :disabled="disabled" @click="stepZoom(-1)">＋</button><button type="button" :disabled="disabled" @click="stepZoom(1)">－</button><button type="button" :disabled="disabled" @click="reset">重置视图</button><span>{{zoomText}}</span></div>
    <div class="map-toolbar"><label>三维范围 <select v-model.number="preset" :disabled="disabled" @change="place((selection.minX + selection.width / 2) * 16, (selection.minZ + selection.depth / 2) * 16)"><option v-for="side in [1,3,5,8]" :key="side" :value="side">{{side}}×{{side}} 区块</option></select></label><button type="button" :disabled="disabled" @click="emit('select', selection)">生成三维</button><button v-if="completion.retryable" type="button" :disabled="disabled" @click="scheduleTiles(0, true)">重试当前视口</button><span role="status">{{completion.text}}</span></div>
    <p v-if="loadError" role="alert" class="error">地图视口读取失败：{{loadError.message}}（{{loadError.code}}）</p>
    <div class="map-viewport-host"><canvas ref="canvas" :tabindex="disabled ? -1 : 0" aria-label="可平移和缩放的二维世界地图" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="pointerUp" @pointerleave="hover=null; draw()" @wheel="wheel" @keydown="keyDown" /></div>
    <figcaption>拖动自由浏览，滚轮在光标位置缩放；深灰为未加载，灰色为真实缺失，琥珀色为不支持，红色为损坏或整瓦片失败。{{hoverText}}</figcaption>
    <p>白色十字：加载中心；黄色十字：世界出生点；蓝色框：三维选区。当前选区：区块 X {{selection.minX}}..{{selection.minX + selection.width - 1}}，Z {{selection.minZ}}..{{selection.minZ + selection.depth - 1}}。</p>
  </figure>
</template>
