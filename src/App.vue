<script setup lang="ts">
import { computed, onMounted, onUnmounted } from "vue";
import MapViewport from "./components/MapViewport.vue";
import VoxelViewer from "./components/VoxelViewer.vue";
import { useWorldExplorer } from "./composables/useWorldExplorer";

const explorer = useWorldExplorer();
const {
  status, statusError, businessError, phase, operation, busy, cancellable,
  world, pendingWorld, mesh, activeMeshRect, hasRetainedView, canInteractWithMap,
  initialize, chooseWorld, selectRect, cancel, close,
  library, scanning, scanDirectory,
} = explorer;

const meshTargetFailureCount = computed(() => mesh.value?.metadata.warnings.filter((warning) => (
  warning.kind === "targetUnavailable" && warning.failure !== "missing"
)).length ?? 0);

const phaseText = computed(() => ({
  idle: "尚未选择世界。",
  selecting: "正在选择并验证世界目录…",
  "selection-cancelled": "未选择新的世界；现有完整视图保持不变。",
  "selection-error": "世界目录选择或验证失败。",
  "map-ready": "世界会话已建立，二维地图按当前视口渐进加载。",
  "mesh-loading": activeMeshRect.value
    ? `正在生成 ${activeMeshRect.value.width}×${activeMeshRect.value.depth} 区块的简化三维…`
    : "正在生成简化三维…",
  "mesh-ready": "简化三维已生成。",
  "mesh-partial": `简化三维已部分生成：${meshTargetFailureCount.value} 个目标区块读取失败。`,
  "mesh-cancelled": "三维生成已取消或结果已过期；原有完整三维保持不变。",
  "mesh-error": "三维生成失败；原有完整三维保持不变。",
  closing: "正在关闭并取消后台任务…",
}[phase.value]));

onMounted(() => { void initialize(); });
onUnmounted(close);
</script>

<template>
  <main class="app-shell">
    <header class="app-header">
      <div><h1>MC World Explorer</h1><p>V0.7.3 只读世界浏览</p></div>
      <span class="scope-badge">只读 · 主世界</span>
    </header>
    <section class="workspace" aria-labelledby="world-heading">
      <h2 id="world-heading">主世界浏览</h2>
      <p class="scope-summary">浏览范围：二维地图可自由平移并按视口缩放加载；选区最大 8×8 区块三维。世界存档始终只读。</p>
      <div class="actions">
        <button type="button" :disabled="busy" @click="scanDirectory">{{ scanning ? "正在扫描目录…" : "选择存档总目录" }}</button>
        <button type="button" :disabled="busy" @click="chooseWorld()">{{world ? "切换世界" : "选择世界目录"}}</button>
        <button v-if="cancellable" type="button" class="secondary" @click="cancel">取消当前任务</button>
      </div>
      <p class="scope-summary">可选择 .minecraft、versions、实例、saves 或单个存档目录，扫描后从列表快速打开存档。</p>
      <section v-if="library" aria-label="已发现的存档" class="world-library">
        <p>已发现 {{library.worlds.length}} 个存档 · {{library.rootPath}}</p>
        <p v-if="library.skipped">有 {{library.skipped}} 项读取失败，其他存档仍可选择。</p>
        <p v-if="!library.worlds.length">没有找到可读取的存档。请选择 .minecraft、versions、实例或 saves 目录。</p>
        <ul>
          <li v-for="entry in library.worlds" :key="entry.id">
            <button type="button" :disabled="busy" @click="chooseWorld(entry.id)">{{entry.group}} · {{entry.name}}</button>
            <span class="path">{{entry.displayPath}}</span>
          </li>
        </ul>
      </section>
      <p role="status" aria-live="polite" class="phase-status" :data-phase="phase">{{ phaseText }}</p>
      <p v-if="businessError" role="alert" class="error">{{ businessError.message }}（{{ businessError.code }}）</p>
      <p v-if="pendingWorld" class="pending-world">正在提交：<strong>{{ pendingWorld.name || "未命名世界" }}</strong></p>
      <template v-if="world">
        <p><strong>{{world.name||"未命名世界"}}</strong> · 加载中心 {{world.loadAnchor.x}}, {{world.loadAnchor.y}}, {{world.loadAnchor.z}}</p>
        <p v-if="world.worldSpawn">世界出生点 {{world.worldSpawn.x}}, {{world.worldSpawn.y}}, {{world.worldSpawn.z}}</p>
        <p class="path">{{world.displayPath}}</p>
      </template>
      <p v-if="hasRetainedView" class="retained-notice">下方为上一次完整结果，仅保留显示；新世界完成提交前不可交互。</p>
      <MapViewport v-if="world" :world="world" :disabled="!canInteractWithMap" @select="selectRect"/>
      <VoxelViewer v-if="mesh" :data="mesh" :disabled="hasRetainedView" @close="mesh = null"/>
    </section>
    <details class="backend-status" :aria-busy="!status && !statusError">
      <summary>后端与便携目录</summary>
      <p v-if="statusError" role="alert" class="error">连接失败：{{ statusError.message }}（{{ statusError.code }}）</p>
      <p v-else role="status">{{ status ? "后端就绪" : "正在连接后端…" }}</p>
      <template v-if="status">
        <p>后端版本：{{ status.appVersion }}</p>
        <dl>
          <div><dt>程序根目录</dt><dd>{{ status.portablePaths.root }}</dd></div>
          <div><dt>缓存</dt><dd>{{ status.portablePaths.cache }}</dd></div>
          <div><dt>日志</dt><dd>{{ status.portablePaths.logs }}</dd></div>
          <div><dt>导出</dt><dd>{{ status.portablePaths.exports }}</dd></div>
          <div><dt>配置</dt><dd>{{ status.portablePaths.config }}</dd></div>
        </dl>
      </template>
    </details>
    <p class="boundary">程序启动时只会在 EXE 所在便携目录准备 WebView 数据；世界路径只由 Rust 目录选择器取得，存档始终只读。</p>
    <p class="boundary">当前已接入主世界二维瓦片浏览和最大 8×8 区块的纯色简化三维；维度、高度层、多人和完整标记等仍在按 Java 基线迁移，不代表 V0.6 全部功能已对等。</p>
  </main>
</template>
