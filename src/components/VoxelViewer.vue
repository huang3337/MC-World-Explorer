<script setup lang="ts">
import { computed, toRef, watch } from "vue";
import type { MeshData } from "../protocol/mesh-v1";
import { useVoxelScene } from "../composables/useVoxelScene";

const props = withDefaults(defineProps<{ data: MeshData; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ close: [] }>();
const {
  host,
  state,
  presentedData,
  resetView,
  pointerDown,
  pointerMove,
  pointerUp,
  cancelPointer,
  wheel,
  keyDown,
} = useVoxelScene(toRef(props, "data"));

const summary = computed(() => (
  `${presentedData.value.metadata.blockCount} 方块，${presentedData.value.metadata.faceCount} 面，`
  + `${presentedData.value.positions.length / 3} 顶点，${presentedData.value.batches.length / 8} 批次`
));
const targetFailureCount = computed(() => presentedData.value.metadata.warnings.filter((warning) => (
  warning.kind === "targetUnavailable" && warning.failure !== "missing"
)).length);
const warningSummary = computed(() => {
  const informationCount = presentedData.value.metadata.warnings.length - targetFailureCount.value;
  if (targetFailureCount.value === 0 && informationCount === 0) return "没有读取失败或兼容提示。";
  if (targetFailureCount.value === 0) return `场景读取成功，另有 ${informationCount} 项降级或兼容信息。`;
  return `${targetFailureCount.value} 项目标读取失败，另有 ${informationCount} 项降级或兼容信息。`;
});
const visibleWarnings = computed(() => presentedData.value.metadata.warnings.slice(0, 8));
const hiddenWarningCount = computed(() => presentedData.value.metadata.warnings.length - visibleWarnings.value.length);
function warningText(warning: MeshData["metadata"]["warnings"][number]): string {
  const location = `区块 X ${warning.chunkX}，Z ${warning.chunkZ}`;
  if (warning.kind === "fallbackBlock") {
    return `${location}：未知方块 ${warning.blockName} 使用回退颜色，共 ${warning.occurrences} 处。`;
  }
  const scope = warning.kind === "targetUnavailable" ? "目标区块" : "相邻区块";
  const failure = warning.failure === "missing" ? "缺失" : warning.failure === "unsupported" ? "不支持" : "损坏";
  return `${location}：${scope}${failure}，共 ${warning.occurrences} 项。`;
}
function whenEnabled<T extends Event>(handler: (event: T) => void): (event: T) => void {
  return (event) => { if (!props.disabled) handler(event); };
}
const enabledPointerDown = whenEnabled(pointerDown);
const enabledPointerMove = whenEnabled(pointerMove);
const enabledWheel = whenEnabled(wheel);
const enabledKeyDown = whenEnabled(keyDown);
watch(() => props.disabled, (disabled) => { if (disabled) cancelPointer(); });
function handleKeyDown(event: KeyboardEvent): void {
  if (props.disabled) return;
  if (event.key === "Escape") {
    cancelPointer();
    event.preventDefault();
    event.stopPropagation();
    emit("close");
  } else enabledKeyDown(event);
}
function enabledReset(): void {
  if (!props.disabled) resetView();
}
const stateText = computed(() => {
  if (state.value === "context-lost") return "WebGL 上下文已丢失，正在等待浏览器恢复。";
  if (state.value === "error") return "无法建立新的简化三维场景，已保留此前完整视图（如果存在）。";
  return "简化三维已就绪。";
});
</script>

<template>
  <section class="voxel-viewer" :class="{ 'is-disabled': disabled }" aria-labelledby="voxel-heading" :aria-disabled="disabled">
    <h2 id="voxel-heading">简化三维</h2>
    <p>{{ summary }}</p>
    <p id="voxel-state" role="status" aria-live="polite">{{ stateText }}</p>
    <p id="voxel-warnings">{{ warningSummary }}</p>
    <ul v-if="visibleWarnings.length" class="voxel-warnings" aria-label="三维读取与兼容详情">
      <li v-for="warning in visibleWarnings" :key="`${warning.kind}:${warning.chunkX}:${warning.chunkZ}:${warning.blockName}`">
        {{ warningText(warning) }}
      </li>
      <li v-if="hiddenWarningCount">另有 {{ hiddenWarningCount }} 项聚合信息未展开。</li>
    </ul>
    <div
      ref="host"
      class="voxel-host"
      :tabindex="disabled ? -1 : 0"
      aria-label="简化三维区块视图"
      aria-describedby="voxel-state voxel-warnings voxel-help"
      @pointerdown="enabledPointerDown"
      @pointermove="enabledPointerMove"
      @pointerup="pointerUp"
      @pointercancel="cancelPointer"
      @lostpointercapture="cancelPointer"
      @blur="cancelPointer"
      @contextmenu.prevent
      @wheel="enabledWheel"
      @keydown="handleKeyDown"
    />
    <button type="button" class="secondary voxel-reset" :disabled="disabled" @click="enabledReset">重置三维视角</button>
    <p id="voxel-help" class="voxel-help">
      {{ disabled ? "当前三维仅保留显示，完成新世界加载后才可交互。" : "左键移动超过 4 像素后旋转，单击暂不选择；右键拖动有限平移，滚轮围绕当前中心缩放。方向键旋转，＋/－缩放，R / Home 重置，Esc 关闭三维预览。" }}
    </p>
  </section>
</template>
