<script setup lang="ts">
import { computed, ref, toRef, watch } from "vue";
import type { SurfaceData } from "../protocol/surface-v1";
import type { ChunkRect } from "../services/types";
import { cellFromClient, useSurfaceCanvas, type GridCell, type LocalSelection } from "../composables/useSurfaceCanvas";

const props = withDefaults(defineProps<{ data: SurfaceData; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ select: [rect: ChunkRect] }>();
const preset = ref(3);
const side = computed(() => Math.sqrt(props.data.colors.length));
const hover = ref<GridCell | null>(null);
const selection = ref<LocalSelection>({ anchorX: 0, anchorZ: 0, focusX: 0, focusZ: 0, minX: 0, minZ: 0, width: 3, depth: 3 });
const { baseCanvas, overlayCanvas } = useSurfaceCanvas(toRef(props, "data"), selection, hover);
const baseX = computed(() => Math.floor(props.data.metadata.originBlockX / 16));
const baseZ = computed(() => Math.floor(props.data.metadata.originBlockZ / 16));
function place(cx: number, cz: number): void {
  const { originBlockX: ox, originBlockZ: oz } = props.data.metadata;
  const size = preset.value;
  const minX = Math.max(Math.ceil(ox / 16), Math.min(Math.floor((ox + side.value) / 16) - size, cx - Math.floor((size - 1) / 2)));
  const minZ = Math.max(Math.ceil(oz / 16), Math.min(Math.floor((oz + side.value) / 16) - size, cz - Math.floor((size - 1) / 2)));
  selection.value = { anchorX: minX - baseX.value, anchorZ: minZ - baseZ.value,
    focusX: minX - baseX.value + size - 1, focusZ: minZ - baseZ.value + size - 1,
    minX: minX - baseX.value, minZ: minZ - baseZ.value, width: size, depth: size };
}
function reset(): void {
  preset.value = 3; hover.value = null;
  place(props.data.metadata.centerChunkX, props.data.metadata.centerChunkZ);
}
watch(() => props.data, reset, { immediate: true });
function changePreset(): void {
  place(baseX.value + selection.value.minX + Math.floor((selection.value.width - 1) / 2),
    baseZ.value + selection.value.minZ + Math.floor((selection.value.depth - 1) / 2));
}
const worldRect = computed<ChunkRect>(() => ({ minX: baseX.value + selection.value.minX,
  minZ: baseZ.value + selection.value.minZ, width: selection.value.width, depth: selection.value.depth }));
function generate(): void { if (!props.disabled) emit("select", worldRect.value); }
function eventCell(event: MouseEvent): GridCell | null {
  return overlayCanvas.value ? cellFromClient(overlayCanvas.value.getBoundingClientRect(), event.clientX, event.clientY, side.value) : null;
}
function point(event: PointerEvent): void {
  if (props.disabled || event.button !== 0) return;
  const cell = eventCell(event); if (!cell) return;
  place(Math.floor((props.data.metadata.originBlockX + cell.x) / 16),
    Math.floor((props.data.metadata.originBlockZ + cell.z) / 16));
  event.preventDefault();
}
function keyDown(event: KeyboardEvent): void {
  if (props.disabled) return;
  const moves: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
  const move = moves[event.key];
  if (move) {
    place(worldRect.value.minX + Math.floor((preset.value - 1) / 2) + move[0],
      worldRect.value.minZ + Math.floor((preset.value - 1) / 2) + move[1]);
    event.preventDefault();
  } else if (event.key === "Enter" || event.key === " ") { generate(); event.preventDefault(); }
}
const selectionText = computed(() => {
  const r = worldRect.value; const counts = [0, 0, 0, 0];
  for (let z = r.minZ; z < r.minZ + r.depth; z++) for (let x = r.minX; x < r.minX + r.width; x++) {
    const index = (z * 16 - props.data.metadata.originBlockZ) * side.value + x * 16 - props.data.metadata.originBlockX;
    counts[props.data.statuses[index]]++;
  }
  return `当前选区：区块 X ${r.minX}..${r.minX + r.width - 1}，Z ${r.minZ}..${r.minZ + r.depth - 1}，${r.width}×${r.depth}；正常 ${counts[0]}，缺失 ${counts[1]}，不支持 ${counts[2]}，损坏 ${counts[3]}。`;
});
const hoverText = computed(() => {
  const h = hover.value; if (!h) return "悬停：无。";
  const i = h.z * side.value + h.x;
  const x = props.data.metadata.originBlockX + h.x, z = props.data.metadata.originBlockZ + h.z;
  const detail = props.data.statuses[i] === 0 ? `表面 Y ${props.data.heights[i]}，颜色 #${props.data.colors[i].toString(16).padStart(6, "0").toUpperCase()}` : "表面数据不可用";
  return `悬停：世界 X ${x}，Z ${z}；区块 X ${Math.floor(x / 16)}，Z ${Math.floor(z / 16)}；${detail}；${["正常", "缺失", "不支持", "损坏"][props.data.statuses[i]]}。`;
});
</script>
<template>
  <figure class="surface-map" :class="{ 'is-disabled': disabled }" :aria-disabled="disabled">
    <div class="actions">
      <label>三维范围
        <select v-model.number="preset" :disabled="disabled" @change="changePreset">
          <option v-for="size in [1, 3, 5, 8]" :key="size" :value="size">{{size}}×{{size}} 区块</option>
        </select>
      </label>
      <button type="button" :disabled="disabled" @click="generate">生成三维</button>
    </div>
    <div class="surface-canvas-stack">
      <canvas ref="baseCanvas" class="surface-base" aria-hidden="true" />
      <canvas ref="overlayCanvas" class="surface-overlay" :tabindex="disabled ? -1 : 0"
        aria-label="默认中心周围二维表面图；方向为西到东、北到南"
        aria-describedby="surface-caption surface-selection surface-hover surface-help"
        @pointerdown="point" @pointermove="hover = disabled ? null : eventCell($event)"
        @pointerleave="hover = null" @keydown="keyDown" />
    </div>
    <figcaption id="surface-caption">逻辑范围 {{side}}×{{side}} 方块（西→东为 X，北→南为 Z）；每 16 格为一个区块。</figcaption>
    <p id="surface-selection" role="status" aria-live="polite">{{selectionText}}</p>
    <p id="surface-hover">{{hoverText}}</p>
    <p>白色十字：当前默认中心；黄色斜十字：世界出生点；蓝色实线框：当前选区。○ 未生成，△ 不支持，× 损坏。</p>
    <p id="surface-help">{{disabled ? "当前视图仅保留显示，完成新世界加载后才可重新选择。" : "选择范围后点击地图定位；方向键移动，点击生成三维或按 Enter / 空格确认。"}}</p>
  </figure>
</template>
