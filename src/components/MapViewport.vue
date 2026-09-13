<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import type { SurfaceData } from "../protocol/surface-v1";
import { MAP_TILE_PIXELS, type MapTileData, type MapTileZoom } from "../protocol/map-tile-v1";
import type { ChunkRect, DisplayError } from "../services/types";
import { loadMapTile } from "../services/backend";
import { DISPLAY_ZOOMS, nearestDisplayZoom, tileZoom, visibleTiles, zoomAt, type ViewportState } from "../composables/mapViewportMath";

const props = withDefaults(defineProps<{ data: SurfaceData; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ select: [rect: ChunkRect] }>();
const canvas = ref<HTMLCanvasElement>();
const preset = ref(3), locateX = ref(0), locateZ = ref(0);
const view = ref<ViewportState>({ centerX: 0, centerZ: 0, blocksPerPixel: 2 });
const committedZoom = ref<MapTileZoom>(2);
const selection = ref<ChunkRect>({ minX: 0, minZ: 0, width: 3, depth: 3 });
const hover = ref<{ x: number; z: number } | null>(null);
const loading = ref(0);
const loadError = ref<DisplayError | null>(null);
const tiles = shallowRef(new Map<string, { data: MapTileData; image: HTMLCanvasElement }>());
let fallback: HTMLCanvasElement | null = null, resizeObserver: ResizeObserver | null = null;
let generation = 0, requestTimer: number | null = null, zoomTimer: number | null = null;
let pointerId: number | null = null, pressX = 0, pressY = 0, lastX = 0, lastY = 0, dragging = false;

const keyOf = (x: number, z: number, bpp: number) => `${bpp}:${x}:${z}`;
function size() { const rect = canvas.value?.getBoundingClientRect(); return { width: Math.max(1, rect?.width || 800), height: Math.max(1, rect?.height || 520) }; }
function worldAt(clientX: number, clientY: number) {
  const rect = canvas.value?.getBoundingClientRect(); if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return { x: view.value.centerX + (clientX - rect.left - rect.width / 2) * view.value.blocksPerPixel, z: view.value.centerZ + (clientY - rect.top - rect.height / 2) * view.value.blocksPerPixel };
}
function screen(x: number, z: number, width: number, height: number): [number, number] {
  return [width / 2 + (x - view.value.centerX) / view.value.blocksPerPixel, height / 2 + (z - view.value.centerZ) / view.value.blocksPerPixel];
}
function imageFor(colors: Uint32Array, statuses: Uint8Array, side: number): HTMLCanvasElement {
  const target = document.createElement("canvas"); target.width = side; target.height = side;
  const context = target.getContext("2d")!; const image = context.createImageData(side, side); const failed = [0, 0x20262b, 0x66501f, 0x6d2834];
  for (let i = 0; i < colors.length; i += 1) {
    const rgb = statuses[i] === 0 ? colors[i] : failed[statuses[i]];
    image.data[i * 4] = rgb >> 16 & 255; image.data[i * 4 + 1] = rgb >> 8 & 255; image.data[i * 4 + 2] = rgb & 255; image.data[i * 4 + 3] = 255;
  }
  context.putImageData(image, 0, 0); return target;
}
function draw() {
  const target = canvas.value; if (!target) return; const { width, height } = size(); const dpr = Math.min(2, Math.max(1, globalThis.devicePixelRatio || 1));
  if (target.width !== Math.round(width * dpr)) target.width = Math.round(width * dpr); if (target.height !== Math.round(height * dpr)) target.height = Math.round(height * dpr);
  const context = target.getContext("2d"); if (!context) return; context.setTransform(dpr, 0, 0, dpr, 0, 0); context.imageSmoothingEnabled = view.value.blocksPerPixel !== committedZoom.value;
  context.fillStyle = "#20262b"; context.fillRect(0, 0, width, height);
  if (fallback) {
    const side = Math.sqrt(props.data.colors.length), [x, z] = screen(props.data.metadata.originBlockX, props.data.metadata.originBlockZ, width, height), display = side / view.value.blocksPerPixel;
    context.drawImage(fallback, Math.floor(x), Math.floor(z), Math.ceil(display), Math.ceil(display));
  }
  const tileSize = MAP_TILE_PIXELS * committedZoom.value;
  for (const tile of tiles.value.values()) if (tile.data.metadata.blocksPerPixel === committedZoom.value) {
    const [x, z] = screen(tile.data.metadata.originBlockX, tile.data.metadata.originBlockZ, width, height), display = tileSize / view.value.blocksPerPixel;
    context.drawImage(tile.image, Math.floor(x), Math.floor(z), Math.ceil(display), Math.ceil(display));
  }
  if (view.value.blocksPerPixel <= 4) {
    const minX = view.value.centerX - width * view.value.blocksPerPixel / 2, maxX = view.value.centerX + width * view.value.blocksPerPixel / 2;
    const minZ = view.value.centerZ - height * view.value.blocksPerPixel / 2, maxZ = view.value.centerZ + height * view.value.blocksPerPixel / 2; context.beginPath();
    for (let x = Math.floor(minX / 16) * 16; x <= maxX; x += 16) { const sx = screen(x, 0, width, height)[0]; context.moveTo(sx, 0); context.lineTo(sx, height); }
    for (let z = Math.floor(minZ / 16) * 16; z <= maxZ; z += 16) { const sz = screen(0, z, width, height)[1]; context.moveTo(0, sz); context.lineTo(width, sz); }
    context.strokeStyle = "rgba(235,244,250,.28)"; context.lineWidth = 1; context.stroke();
  }
  const cross = (x: number, z: number, color: string, radius: number) => { const [sx, sz] = screen(x + .5, z + .5, width, height); context.beginPath(); context.moveTo(sx - radius, sz); context.lineTo(sx + radius, sz); context.moveTo(sx, sz - radius); context.lineTo(sx, sz + radius); context.strokeStyle = color; context.lineWidth = 2; context.stroke(); };
  cross(props.data.metadata.centerX, props.data.metadata.centerZ, "#fff", 7); const spawn = props.data.metadata.worldSpawn; if (spawn) cross(spawn.x, spawn.z, "#fbbf24", 5);
  const [sx, sz] = screen(selection.value.minX * 16, selection.value.minZ * 16, width, height), sw = selection.value.width * 16 / view.value.blocksPerPixel, sh = selection.value.depth * 16 / view.value.blocksPerPixel;
  context.fillStyle = "rgba(79,155,200,.22)"; context.fillRect(sx, sz, sw, sh); context.strokeStyle = "#7dd3fc"; context.lineWidth = 2; context.strokeRect(sx, sz, sw, sh);
}
function scheduleTiles(delay = 80) { if (requestTimer !== null) clearTimeout(requestTimer); requestTimer = globalThis.setTimeout(() => { requestTimer = null; void loadVisible(); }, delay); }
async function loadVisible() {
  const own = ++generation, { width, height } = size(); const targets = visibleTiles(view.value, width, height, committedZoom.value);
  loading.value = targets.filter(t => !tiles.value.has(keyOf(t.tileX, t.tileZ, t.blocksPerPixel))).length; loadError.value = null;
  for (const target of targets) {
    if (own !== generation) return; const key = keyOf(target.tileX, target.tileZ, target.blocksPerPixel); if (tiles.value.has(key)) continue;
    try {
      const data = await loadMapTile(props.data.metadata.sessionId, target.tileX, target.tileZ, target.blocksPerPixel); if (own !== generation) return;
      const next = new Map(tiles.value); next.set(key, { data, image: imageFor(data.colors, data.statuses, MAP_TILE_PIXELS) }); while (next.size > 64) next.delete(next.keys().next().value!); tiles.value = next; draw();
    } catch (reason) { const error = reason as DisplayError; if (!['TASK_CANCELLED','TASK_OUTDATED','SESSION_OUTDATED'].includes(error.code)) loadError.value = error; }
    finally { if (own === generation) loading.value = Math.max(0, loading.value - 1); }
  }
}
function place(worldX: number, worldZ: number) { const side = preset.value, cx = Math.floor(worldX / 16), cz = Math.floor(worldZ / 16); selection.value = { minX: cx - Math.floor((side - 1) / 2), minZ: cz - Math.floor((side - 1) / 2), width: side, depth: side }; draw(); }
function reset() {
  generation += 1; tiles.value = new Map(); view.value = { centerX: props.data.metadata.centerX, centerZ: props.data.metadata.centerZ, blocksPerPixel: 2 }; committedZoom.value = 2; preset.value = 3;
  fallback = imageFor(props.data.colors, props.data.statuses, Math.sqrt(props.data.colors.length)); place(view.value.centerX, view.value.centerZ); nextTick(() => { draw(); scheduleTiles(0); });
}
function pointerDown(event: PointerEvent) { if (props.disabled || event.button !== 0 || pointerId !== null) return; pointerId = event.pointerId; pressX = lastX = event.clientX; pressY = lastY = event.clientY; dragging = false; canvas.value?.setPointerCapture?.(event.pointerId); canvas.value?.focus({ preventScroll: true }); event.preventDefault(); }
function pointerMove(event: PointerEvent) {
  hover.value = worldAt(event.clientX, event.clientY); if (pointerId !== event.pointerId) { draw(); return; }
  if (!dragging && Math.hypot(event.clientX - pressX, event.clientY - pressY) <= 4) return; dragging = true;
  view.value = { ...view.value, centerX: view.value.centerX - (event.clientX - lastX) * view.value.blocksPerPixel, centerZ: view.value.centerZ - (event.clientY - lastY) * view.value.blocksPerPixel }; lastX = event.clientX; lastY = event.clientY; draw(); scheduleTiles(); event.preventDefault();
}
function pointerUp(event: PointerEvent) { if (pointerId !== event.pointerId) return; if (!dragging) { const point = worldAt(event.clientX, event.clientY); if (point) place(point.x, point.z); } pointerId = null; dragging = false; if (canvas.value?.hasPointerCapture?.(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId); }
function wheel(event: WheelEvent) {
  if (props.disabled) return; const rect = canvas.value!.getBoundingClientRect(); const next = Math.max(.25, Math.min(16, view.value.blocksPerPixel * Math.exp(event.deltaY / 240)));
  view.value = zoomAt(view.value, next, event.clientX - rect.left, event.clientY - rect.top, rect.width, rect.height); draw(); event.preventDefault(); if (zoomTimer !== null) clearTimeout(zoomTimer);
  zoomTimer = globalThis.setTimeout(() => { zoomTimer = null; const settled = nearestDisplayZoom(view.value.blocksPerPixel); view.value = zoomAt(view.value, settled, event.clientX - rect.left, event.clientY - rect.top, rect.width, rect.height); committedZoom.value = tileZoom(settled); draw(); scheduleTiles(0); }, 150);
}
function stepZoom(direction: -1 | 1) { const current = nearestDisplayZoom(view.value.blocksPerPixel), index = DISPLAY_ZOOMS.indexOf(current), next = DISPLAY_ZOOMS[Math.max(0, Math.min(DISPLAY_ZOOMS.length - 1, index + direction))]; const { width, height } = size(); view.value = zoomAt(view.value, next, width / 2, height / 2, width, height); committedZoom.value = tileZoom(next); draw(); scheduleTiles(0); }
function locate() { view.value = { ...view.value, centerX: locateX.value, centerZ: locateZ.value }; draw(); scheduleTiles(0); }
function keyDown(event: KeyboardEvent) { const moves: Record<string, [number, number]> = { ArrowLeft: [-1,0], ArrowRight: [1,0], ArrowUp: [0,-1], ArrowDown: [0,1] }, move = moves[event.key]; if (move && !props.disabled) { view.value = { ...view.value, centerX: view.value.centerX + move[0] * 16, centerZ: view.value.centerZ + move[1] * 16 }; draw(); scheduleTiles(); event.preventDefault(); } }
const hoverText = computed(() => hover.value ? `世界 X ${Math.floor(hover.value.x)}，Z ${Math.floor(hover.value.z)}` : "悬停：无");
const zoomText = computed(() => `${view.value.blocksPerPixel.toFixed(view.value.blocksPerPixel < 1 ? 2 : 0)} 方块/像素`);
watch(() => props.data, reset, { immediate: true }); watch(() => props.disabled, disabled => { if (disabled) { pointerId = null; dragging = false; } });
onMounted(() => { if (typeof ResizeObserver !== "undefined" && canvas.value?.parentElement) { resizeObserver = new ResizeObserver(() => { draw(); scheduleTiles(); }); resizeObserver.observe(canvas.value.parentElement); } draw(); scheduleTiles(0); });
onBeforeUnmount(() => { generation += 1; resizeObserver?.disconnect(); if (requestTimer !== null) clearTimeout(requestTimer); if (zoomTimer !== null) clearTimeout(zoomTimer); fallback = null; tiles.value.clear(); });
</script>

<template>
  <figure class="surface-map" :class="{ 'is-disabled': disabled }" :aria-disabled="disabled">
    <div class="map-toolbar"><label>X <input v-model.number="locateX" type="number" :disabled="disabled" @keyup.enter="locate"></label><label>Z <input v-model.number="locateZ" type="number" :disabled="disabled" @keyup.enter="locate"></label><button type="button" :disabled="disabled" @click="locate">定位</button><button type="button" :disabled="disabled" @click="stepZoom(-1)">＋</button><button type="button" :disabled="disabled" @click="stepZoom(1)">－</button><button type="button" :disabled="disabled" @click="reset">重置视图</button><span>{{zoomText}}</span></div>
    <div class="map-toolbar"><label>三维范围 <select v-model.number="preset" :disabled="disabled" @change="place((selection.minX + selection.width / 2) * 16, (selection.minZ + selection.depth / 2) * 16)"><option v-for="side in [1,3,5,8]" :key="side" :value="side">{{side}}×{{side}} 区块</option></select></label><button type="button" :disabled="disabled" @click="emit('select', selection)">生成三维</button><span role="status">{{loading ? `正在加载 ${loading} 个地图瓦片…` : "当前视口瓦片已加载"}}</span></div>
    <p v-if="loadError" role="alert" class="error">地图瓦片读取失败：{{loadError.message}}（{{loadError.code}}）</p>
    <div class="map-viewport-host"><canvas ref="canvas" :tabindex="disabled ? -1 : 0" aria-label="可平移和缩放的二维世界地图" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="pointerUp" @pointerleave="hover=null; draw()" @wheel="wheel" @keydown="keyDown" /></div>
    <figcaption>拖动自由浏览，滚轮在光标位置缩放；地图按视口加载 256×256 瓦片。{{hoverText}}</figcaption>
    <p>白色十字：默认中心；黄色十字：世界出生点；蓝色框：三维选区。当前选区：区块 X {{selection.minX}}..{{selection.minX + selection.width - 1}}，Z {{selection.minZ}}..{{selection.minZ + selection.depth - 1}}。</p>
  </figure>
</template>
