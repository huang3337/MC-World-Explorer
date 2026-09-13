# 代码审查：src/components/VoxelViewer.vue

- **审查日期**：2026-09-08
- **审查工具**：Codex
- **审查范围**：V0.7.2 Task 14 Three.js 场景、相机、上下文与资源生命周期
- **问题总数**：6 个（🔴 0 / 🟠 3 / 🟡 3 / 🟢 0）

### ISSUE-VOXELVIEWER-001：每个批次重复建立 geometry 并复制索引

- **严重程度**：🟠 高
- **类别**：GPU 资源 / 内存
- **文件**：`src/components/VoxelViewer.vue`、`src/composables/useVoxelScene.ts`
- **状态**：已修复

**问题描述**：初版为每个批次建立一份 `BufferGeometry`，重复挂接完整 position/normal，并使用 `indices.slice()` 复制批次索引。

**问题分析**：批次数虽受后端限制，但重复 geometry 和索引复制仍会随批次放大 CPU/GPU 资源；协议已经提供连续批次范围，无需建立多份网格数据。

**实际修改**：使用单个 geometry 直接引用协议 TypedArray，以 `addGroup(firstIndex, indexCount, materialIndex)` 表达有限批次；每批次只建立对应纯色材质，不创建每方块 Mesh。

### ISSUE-VOXELVIEWER-002：更新时先销毁旧场景，建场失败无法保留完整视图

- **严重程度**：🟠 高
- **类别**：状态原子性 / 竞态
- **文件**：`src/components/VoxelViewer.vue`、`src/composables/useVoxelScene.ts`
- **状态**：已修复

**问题描述**：初版 `build()` 首先调用 `dispose()`，随后才建立新 renderer 和场景；任一构造或渲染失败都会清空此前完整视图。快速连续更新时也没有 pending 身份，较旧结果可能晚于新失败重新出现。

**问题分析**：DECISION-021/023 要求新完整场景成功后再替换旧场景；失败和过期结果都不能破坏或复活页面已提交状态。

**实际修改**：新 bundle 先进入单 pending 槽，首帧成功后才提交 `presentedData` 并释放旧 bundle；新更新先释放旧 pending，更新建场失败会取消对应 RAF 并保留 current。渲染失败只释放新资源，摘要、警告和画面继续对应旧完整结果。

### ISSUE-VOXELVIEWER-003：相机和渲染尺寸固定，无法适配网格 bounds 或进行受限观察

- **严重程度**：🟡 中
- **类别**：交互 / 显示正确性
- **文件**：`src/components/VoxelViewer.vue`、`src/composables/useVoxelScene.ts`
- **状态**：已修复

**问题描述**：初版固定使用 `560×360`、相机位置 `(48,96,96)` 和目标 `(16,64,16)`，不同选区高度、空场景、容器大小或 DPR 下可能裁切、浪费视野或显示模糊，也没有旋转和缩放入口。

**问题分析**：V0.7.2 不实现自由移动，但必须根据可信 bounds 自动 fit，并围绕固定目标提供有限旋转、缩放和可访问的重置操作。

**实际修改**：按 bounds 包围半径及横/纵 FOV 计算 center、distance、near/far；ResizeObserver 与 DPR（上限 2）同步 renderer。指针拖动/滚轮和方向键/加减键控制固定目标环绕与缩放，Home 和按钮恢复自动 fit。

### ISSUE-VOXELVIEWER-004：缺少按需渲染、WebGL 上下文恢复和完整释放闭环

- **严重程度**：🟠 高
- **类别**：资源生命周期
- **文件**：`src/components/VoxelViewer.vue`、`src/composables/useVoxelScene.ts`
- **状态**：已修复

**问题描述**：初版每次数据变化销毁并重建 renderer，但没有 ResizeObserver、RAF 合并、`webglcontextlost/restored` 处理、初始化半途失败清理或显式 context 释放证据。

**问题分析**：WebGL 资源和事件监听器必须随场景替换及组件卸载确定释放；上下文丢失期间继续排帧无效，空闲持续动画又会违反轻量化边界。

**实际修改**：renderer 在组件生命周期内复用，交互与 resize 只合并一个 RAF且不建立动画循环；context loss 阻止默认行为并暂停帧，恢复后重置状态并按需重绘。初始化失败及卸载统一取消 RAF、断开 observer、移除监听、释放 pending/current geometry 与材质、dispose renderer 并 forceContextLoss。

### ISSUE-VOXELVIEWER-005：三维视图没有可解释状态和局部警告详情

- **严重程度**：🟡 中
- **类别**：可访问性 / 可诊断性
- **文件**：`src/components/VoxelViewer.vue`
- **状态**：已修复

**问题描述**：初版只显示顶点和索引数量，建场异常被静默吞掉；局部失败、回退方块、上下文丢失和键盘操作都没有真实 DOM 说明。

**问题分析**：Canvas/WebGL 画面不能作为唯一状态渠道；用户需要区分就绪、保留旧视图、等待上下文恢复及局部降级，但警告 DOM 数量也必须受限。

**实际修改**：增加 blocks/faces/vertices/batches 摘要、live 状态、操作说明和有坐标的警告详情；最多展开 8 项并说明剩余聚合数量，Vue 文本转义保持后端方块名称安全。

- **解决日期**：2026-09-08。
- **验证证据**：Task 14 新增 13 项测试，覆盖 TypedArray/group/材质、空场景、bounds fit、原子提交、pending 失败、按需帧、DPR、context loss/restore、初始化及卸载释放和警告上限；前端共 74 项测试通过。

### ISSUE-VOXELVIEWER-006：运行期 resize 异常会缓存未成功的尺寸并逃逸事件回调

- **严重程度**：🟡 中
- **类别**：WebGL 错误恢复
- **文件**：`src/composables/useVoxelScene.ts`
- **状态**：已修复

**问题描述**：原 resize 在调用 renderer 前先写入尺寸缓存；`setPixelRatio`、`setSize` 或投影更新抛错后，相同尺寸不会重试，事件回调异常也没有转成可解释状态。

**实际修改**：运行期 resize 全段捕获异常，只在 renderer、camera 和 fit 更新成功后提交缓存；初始化失败仍释放 renderer，运行期失败保留场景并允许下一事件恢复。

- **解决日期**：2026-09-09。
- **验证证据**：合成 `setSize` 首次失败、相同尺寸再次成功并恢复就绪的组件测试通过。
