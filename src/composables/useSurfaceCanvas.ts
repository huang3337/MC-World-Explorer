import { onMounted, onUnmounted, ref, watch, type Ref } from "vue";
import type { SurfaceData } from "../protocol/surface-v1";

export const SURFACE_SIZE = 144;
export const CHUNK_SIZE = 16;
export const MAX_CANVAS_EDGE = 2048;

export interface GridCell {
  readonly x: number;
  readonly z: number;
}

export interface LocalSelection {
  readonly anchorX: number;
  readonly anchorZ: number;
  readonly focusX: number;
  readonly focusZ: number;
  readonly minX: number;
  readonly minZ: number;
  readonly width: number;
  readonly depth: number;
}

export interface CanvasSize {
  readonly width: number;
  readonly height: number;
}

const FAILED_COLORS = [0, 0x2b3138, 0x66501f, 0x6d2834] as const;
const FAILED_LABELS = ["", "○", "△", "×"] as const;

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}

export function canvasSize(cssWidth: number, cssHeight: number, dpr: number): CanvasSize {
  const safeWidth = Number.isFinite(cssWidth) && cssWidth > 0 ? cssWidth : SURFACE_SIZE;
  const safeHeight = Number.isFinite(cssHeight) && cssHeight > 0 ? cssHeight : SURFACE_SIZE;
  const safeDpr = Number.isFinite(dpr) && dpr > 0 ? dpr : 1;
  return {
    width: clamp(Math.round(safeWidth * safeDpr), 1, MAX_CANVAS_EDGE),
    height: clamp(Math.round(safeHeight * safeDpr), 1, MAX_CANVAS_EDGE),
  };
}

export function cellFromClient(
  rect: Pick<DOMRect, "left" | "top" | "width" | "height">,
  clientX: number,
  clientY: number,
  size = SURFACE_SIZE,
): GridCell | null {
  if (!(rect.width > 0) || !(rect.height > 0)
    || !Number.isFinite(clientX) || !Number.isFinite(clientY)) return null;
  return {
    x: clamp(Math.floor(((clientX - rect.left) / rect.width) * size), 0, size - 1),
    z: clamp(Math.floor(((clientY - rect.top) / rect.height) * size), 0, size - 1),
  };
}

export function writeSurfacePixels(data: SurfaceData, destination: Uint8ClampedArray): void {
  if (destination.length !== data.colors.length * 4) {
    throw new RangeError("INVALID_SURFACE_PIXEL_BUFFER");
  }
  for (let index = 0; index < data.colors.length; index += 1) {
    const status = data.statuses[index];
    const rgb = status === 0 ? data.colors[index] : FAILED_COLORS[status];
    destination[index * 4] = (rgb >>> 16) & 0xff;
    destination[index * 4 + 1] = (rgb >>> 8) & 0xff;
    destination[index * 4 + 2] = rgb & 0xff;
    destination[index * 4 + 3] = 0xff;
  }
}

function prepareCanvas(canvas: HTMLCanvasElement): CanvasRenderingContext2D | null {
  const rect = canvas.getBoundingClientRect();
  const size = canvasSize(rect.width, rect.height, globalThis.devicePixelRatio);
  if (canvas.width !== size.width) canvas.width = size.width;
  if (canvas.height !== size.height) canvas.height = size.height;
  const context = canvas.getContext("2d");
  if (context) context.imageSmoothingEnabled = false;
  return context;
}

export function useSurfaceCanvas(
  data: Readonly<Ref<SurfaceData>>,
  selection: Readonly<Ref<LocalSelection>>,
  hover: Readonly<Ref<GridCell | null>>,
) {
  const baseCanvas = ref<HTMLCanvasElement>();
  const overlayCanvas = ref<HTMLCanvasElement>();
  let pixelCanvas: HTMLCanvasElement | null = null;
  let resizeObserver: ResizeObserver | null = null;
  let lastBaseData: SurfaceData | null = null;
  let lastBaseWidth = 0;
  let lastBaseHeight = 0;

  function drawBase(): void {
    const canvas = baseCanvas.value;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const size = canvasSize(rect.width, rect.height, globalThis.devicePixelRatio);
    if (lastBaseData === data.value
      && lastBaseWidth === size.width
      && lastBaseHeight === size.height) return;
    const context = prepareCanvas(canvas);
    if (!context) return;
    pixelCanvas ??= document.createElement("canvas");
    const side = Math.sqrt(data.value.colors.length);
    pixelCanvas.width = side;
    pixelCanvas.height = side;
    const pixelContext = pixelCanvas.getContext("2d");
    if (!pixelContext) return;
    const image = pixelContext.createImageData(side, side);
    writeSurfacePixels(data.value, image.data);
    pixelContext.putImageData(image, 0, 0);
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(pixelCanvas, 0, 0, canvas.width, canvas.height);
    lastBaseData = data.value;
    lastBaseWidth = canvas.width;
    lastBaseHeight = canvas.height;
  }

  function drawOverlay(): void {
    const canvas = overlayCanvas.value;
    if (!canvas) return;
    const context = prepareCanvas(canvas);
    if (!context) return;
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    const side = Math.sqrt(data.value.colors.length);
    const { originBlockX: ox, originBlockZ: oz } = data.value.metadata;
    const offsetX = Math.floor(ox / 16) * 16 - ox;
    const offsetZ = Math.floor(oz / 16) * 16 - oz;
    context.setTransform(canvas.width / side, 0, 0, canvas.height / side, 0, 0);

    context.font = "5px sans-serif";
    context.textAlign = "center";
    context.textBaseline = "middle";
    for (let z = offsetZ; z < side; z += 16) {
      for (let x = offsetX; x < side; x += 16) {
        const status = data.value.statuses[Math.max(0, z) * side + Math.max(0, x)];
        if (status !== 0) {
          context.fillStyle = "rgba(255, 255, 255, 0.92)";
          context.fillText(FAILED_LABELS[status], x + 8, z + 8);
        }
      }
    }
    context.beginPath();
    for (let x = offsetX; x <= side; x += 16) {
      context.moveTo(x, 0); context.lineTo(x, side);
    }
    for (let z = offsetZ; z <= side; z += 16) {
      context.moveTo(0, z); context.lineTo(side, z);
    }
    context.strokeStyle = "rgba(235, 244, 250, 0.55)";
    context.lineWidth = 0.5;
    context.stroke();

    const centerX = data.value.metadata.centerX - data.value.metadata.originBlockX + 0.5;
    const centerZ = data.value.metadata.centerZ - data.value.metadata.originBlockZ + 0.5;
    context.beginPath();
    context.moveTo(centerX - 6, centerZ);
    context.lineTo(centerX + 6, centerZ);
    context.moveTo(centerX, centerZ - 6);
    context.lineTo(centerX, centerZ + 6);
    context.strokeStyle = "#ffffff";
    context.lineWidth = Math.max(1, side / canvas.width);
    context.stroke();

    const worldSpawn = data.value.metadata.worldSpawn;
    if (worldSpawn) {
      const worldSpawnX = worldSpawn.x - data.value.metadata.originBlockX + 0.5;
      const worldSpawnZ = worldSpawn.z - data.value.metadata.originBlockZ + 0.5;
      if (worldSpawnX >= 0 && worldSpawnX < side && worldSpawnZ >= 0 && worldSpawnZ < side) {
        context.beginPath();
        context.moveTo(worldSpawnX - 2, worldSpawnZ - 2);
        context.lineTo(worldSpawnX + 2, worldSpawnZ + 2);
        context.moveTo(worldSpawnX + 2, worldSpawnZ - 2);
        context.lineTo(worldSpawnX - 2, worldSpawnZ + 2);
        context.strokeStyle = "#fbbf24";
        context.lineWidth = Math.max(1, side / canvas.width);
        context.stroke();
      }
    }

    const selected = selection.value;
    context.fillStyle = "rgba(79, 155, 200, 0.2)";
    context.fillRect(selected.minX * CHUNK_SIZE + offsetX, selected.minZ * CHUNK_SIZE + offsetZ, selected.width * CHUNK_SIZE, selected.depth * CHUNK_SIZE);
    context.strokeStyle = "#7dd3fc";
    context.lineWidth = Math.max(1, side / canvas.width);
    context.strokeRect(selected.minX * CHUNK_SIZE + offsetX + 0.5, selected.minZ * CHUNK_SIZE + offsetZ + 0.5, selected.width * CHUNK_SIZE - 1, selected.depth * CHUNK_SIZE - 1);

    if (hover.value) {
      context.strokeStyle = "#f8fafc";
      context.lineWidth = 0.5;
      context.strokeRect(hover.value.x + 0.25, hover.value.z + 0.25, 0.5, 0.5);
    }
  }

  function resize(): void {
    drawBase();
    drawOverlay();
  }

  onMounted(() => {
    resize();
    if (typeof ResizeObserver !== "undefined" && overlayCanvas.value?.parentElement) {
      resizeObserver = new ResizeObserver(resize);
      resizeObserver.observe(overlayCanvas.value.parentElement);
    }
    globalThis.addEventListener("resize", resize);
  });
  watch(data, resize);
  watch(selection, drawOverlay);
  watch(hover, drawOverlay);
  onUnmounted(() => {
    resizeObserver?.disconnect();
    resizeObserver = null;
    globalThis.removeEventListener("resize", resize);
    if (pixelCanvas) {
      pixelCanvas.width = 0;
      pixelCanvas.height = 0;
      pixelCanvas = null;
    }
    lastBaseData = null;
    lastBaseWidth = 0;
    lastBaseHeight = 0;
  });

  return { baseCanvas, overlayCanvas, drawBase, drawOverlay };
}
