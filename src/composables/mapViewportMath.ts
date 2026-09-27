import type { MapTileZoom } from "../protocol/map-tile-v1";

export const DISPLAY_ZOOMS = [0.25, 0.5, 1, 2, 4, 8, 16] as const;
export const WHEEL_ZOOM_DIVISOR = 240;
export type DisplayZoom = typeof DISPLAY_ZOOMS[number];
export interface ViewportState { centerX: number; centerZ: number; blocksPerPixel: number }
export interface TileCoordinate { tileX: number; tileZ: number; blocksPerPixel: MapTileZoom }

export function tileZoom(displayZoom: number): MapTileZoom {
  return displayZoom <= 1 ? 1 : displayZoom as MapTileZoom;
}
export function zoomAt(state: ViewportState, next: number, pointerX: number, pointerY: number, width: number, height: number): ViewportState {
  const worldX = state.centerX + (pointerX - width / 2) * state.blocksPerPixel;
  const worldZ = state.centerZ + (pointerY - height / 2) * state.blocksPerPixel;
  return { centerX: worldX - (pointerX - width / 2) * next, centerZ: worldZ - (pointerY - height / 2) * next, blocksPerPixel: next };
}
export function nearestDisplayZoom(value: number): DisplayZoom {
  return DISPLAY_ZOOMS.reduce((best, candidate) => Math.abs(Math.log(value / candidate)) < Math.abs(Math.log(value / best)) ? candidate : best);
}
export function wheelVisualZoom(current: number, deltaY: number): number {
  return Math.max(0.25, Math.min(16, current * Math.pow(2, deltaY / WHEEL_ZOOM_DIVISOR)));
}
export function isTemporaryScale(visualBlocksPerPixel: number, committedDisplayZoom: number): boolean {
  return Math.abs(visualBlocksPerPixel - committedDisplayZoom) > 0.000_001;
}
export function displayZoomLabel(displayZoom: number, dataZoom = tileZoom(nearestDisplayZoom(displayZoom))): string {
  const display = displayZoom < 1
    ? `${(1 / displayZoom).toFixed(displayZoom === .25 || displayZoom === .5 ? 0 : 1)} 像素/方块`
    : `${displayZoom.toFixed(Number.isInteger(displayZoom) ? 0 : 2)} 方块/像素`;
  if (Math.abs(displayZoom - dataZoom) <= .000_001) return `显示与数据：${display}`;
  return `显示：${display}；数据：${dataZoom} 方块/像素`;
}
export function visibleTiles(state: ViewportState, width: number, height: number, zoom: MapTileZoom): TileCoordinate[] {
  const size = 256 * zoom;
  const minX = Math.floor((state.centerX - width * state.blocksPerPixel / 2) / size);
  const maxX = Math.floor((state.centerX + width * state.blocksPerPixel / 2) / size);
  const minZ = Math.floor((state.centerZ - height * state.blocksPerPixel / 2) / size);
  const maxZ = Math.floor((state.centerZ + height * state.blocksPerPixel / 2) / size);
  const result: TileCoordinate[] = [];
  for (let z = minZ; z <= maxZ; z += 1) for (let x = minX; x <= maxX; x += 1) result.push({ tileX: x, tileZ: z, blocksPerPixel: zoom });
  return result.sort((a, b) => {
    const ax = (a.tileX + .5) * size - state.centerX, az = (a.tileZ + .5) * size - state.centerZ;
    const bx = (b.tileX + .5) * size - state.centerX, bz = (b.tileZ + .5) * size - state.centerZ;
    return ax * ax + az * az - bx * bx - bz * bz;
  });
}
