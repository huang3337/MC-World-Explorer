<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { getAppStatus, toDisplayError } from "./services/backend";
import type { AppStatus, DisplayError } from "./services/types";

const status = ref<AppStatus | null>(null);
const error = ref<DisplayError | null>(null);
let disposed = false;

onMounted(async () => {
  try {
    const result = await getAppStatus();
    if (!disposed) status.value = result;
  } catch (reason) {
    if (!disposed) error.value = toDisplayError(reason);
  }
});
onUnmounted(() => { disposed = true; });
</script>

<template>
  <main class="app-shell">
    <h1>MC World Explorer</h1>
    <p>V0.7.1 工程迁移基础</p>
    <section aria-labelledby="backend-heading" :aria-busy="!status && !error">
      <h2 id="backend-heading">后端连接</h2>
      <p v-if="error" role="alert" class="error">连接失败：{{ error.message }}（{{ error.code }}）</p>
      <p v-else role="status">{{ status ? "后端就绪" : "正在连接后端…" }}</p>
      <template v-if="status">
        <p>后端版本：{{ status.appVersion }}</p>
        <h2>便携目录</h2>
        <dl>
          <div><dt>程序根目录</dt><dd>{{ status.portablePaths.root }}</dd></div>
          <div><dt>缓存</dt><dd>{{ status.portablePaths.cache }}</dd></div>
          <div><dt>日志</dt><dd>{{ status.portablePaths.logs }}</dd></div>
          <div><dt>导出</dt><dd>{{ status.portablePaths.exports }}</dd></div>
          <div><dt>配置</dt><dd>{{ status.portablePaths.config }}</dd></div>
        </dl>
      </template>
    </section>
    <p class="boundary">本页仅查询状态，不读取 Minecraft 存档。程序启动时只会在 EXE 所在便携目录准备 WebView 数据；存档始终只读。</p>
    <p class="boundary">当前是工程验证页，不代表 V0.6 功能迁移已完成。</p>
  </main>
</template>
