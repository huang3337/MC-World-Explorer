import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { invoke } from "@tauri-apps/api/core";
import App from "./App.vue";

let app: VueApp | undefined;
let canvasSpy: { mockRestore(): void };
beforeEach(() => {
  canvasSpy = vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({
    setTransform: vi.fn(), fillRect: vi.fn(), strokeRect: vi.fn(), drawImage: vi.fn(),
    beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(),
  } as unknown as CanvasRenderingContext2D);
});
afterEach(() => { app?.unmount(); app = undefined; canvasSpy.mockRestore(); });

function mount() {
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(App);
  app.mount(host);
  return host;
}

const status = {
  appVersion: "0.7.3", backendStatus: "ready",
  portablePaths: { root: "D:\\便携目录", cache: "D:\\便携目录\\cache", logs: "D:\\便携目录\\logs", exports: "D:\\便携目录\\exports", config: "D:\\便携目录\\config" },
};

const world = {
  sessionId: 1,
  name: "合成世界",
  displayPath: "D:\\World",
  dataVersion: null,
  centerX: 0,
  centerY: 64,
  centerZ: 0,
  centerSource: "worldSpawn",
  worldSpawn: { x: 0, y: 64, z: 0 },
  loadAnchor: { x: 12, y: 70, z: -8 },
  loadAnchorSource: "playerExit",
};

it("选择总目录显示实例与存档，点击列表通过候选编号打开", async () => {
  vi.mocked(invoke).mockResolvedValueOnce(status).mockResolvedValueOnce({
    rootPath: "D:\\MC\\.minecraft\\versions", skipped: 0,
    worlds: [{ id: 42, name: "雪山", group: "FarmingTales", displayPath: "D:\\MC\\saves\\雪山" }],
  }).mockResolvedValueOnce(world).mockRejectedValueOnce({ code: "TASK_CANCELLED" });
  const host = mount();
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "选择存档总目录")!.click();
  await vi.waitFor(() => expect(host.textContent).toContain("已发现 1 个存档"));
  expect(host.textContent).toContain("FarmingTales · 雪山");
  (host.querySelector(".world-library button") as HTMLButtonElement).click();
  await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith("open_discovered_world", { worldId: 42 }));
  expect(invoke).not.toHaveBeenCalledWith("select_world");
});

it("世界会话成功后直接挂载可交互瓦片地图，不请求固定表面", async () => {
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "app_status") return status;
    if (command === "select_world") return world;
    if (command === "start_map_viewport") return { sessionId: 1, viewportTaskId: 1, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: -1, blocksPerPixel: 2 }] };
    if (command === "next_map_viewport_batch") return new Promise(() => undefined);
    if (command === "cancel_map_viewport") return null;
    if (command === "cancel_active_task") return { cancelled: false };
    throw new Error(`unexpected command: ${command}`);
  });
  const host = mount();
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "选择世界目录")!.click();
  await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith("start_map_viewport", {
    sessionId: 1, centerX: 12, centerZ: -8, viewportWidth: 800, viewportHeight: 520, blocksPerPixel: 2, displayBlocksPerPixel: 2,
  }));
  expect(host.querySelector(".surface-map")?.getAttribute("aria-disabled")).toBe("false");
  expect(vi.mocked(invoke).mock.calls.some(call => call[0] === "load_surface")).toBe(false);
});

it.each([3, 8])("边长 %d 的三维选区生成时显示实际请求范围", async (side) => {
  vi.mocked(invoke).mockImplementation(async command => {
    if (command === "app_status") return status;
    if (command === "select_world") return world;
    if (command === "start_map_viewport" || command === "build_mesh_for_selection") return new Promise(() => undefined);
    if (command === "cancel_active_task") return { cancelled: false };
    throw new Error(`unexpected command: ${command}`);
  });
  const host = mount();
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "选择世界目录")!.click();
  await vi.waitFor(() => expect(host.querySelector(".surface-map")).not.toBeNull());
  const preset = host.querySelector<HTMLSelectElement>(".surface-map select")!;
  preset.value = String(side);
  preset.dispatchEvent(new Event("change", { bubbles: true }));
  await nextTick();
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "生成三维")!.click();
  await vi.waitFor(() => expect(host.querySelector(".phase-status")?.textContent).toBe(`正在生成 ${side}×${side} 区块的简化三维…`));
  expect(invoke).toHaveBeenCalledWith("build_mesh_for_selection", expect.objectContaining({
    chunkRect: expect.objectContaining({ width: side, depth: side }),
  }));
});

it("从加载状态进入就绪，显示版本及五个路径", async () => {
  let resolve!: (value: unknown) => void;
  vi.mocked(invoke).mockReturnValue(new Promise((done) => { resolve = done; }));
  const host = mount();
  expect(host.textContent).toContain("正在连接后端");
  resolve(status);
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  expect(host.textContent).toContain("0.7.3");
  for (const path of Object.values(status.portablePaths)) expect(host.textContent).toContain(path);
  expect(host.textContent).toContain("程序启动时只会在 EXE 所在便携目录准备 WebView 数据");
});

it("连接失败显示固定中文，不泄漏内部错误或路径", async () => {
  vi.mocked(invoke).mockRejectedValue({ code: "IO_ERROR", message: "C:\\secret stack" });
  const host = mount();
  await vi.waitFor(() => expect(host.querySelector('[role="alert"]')?.textContent).toContain("读取来源时发生 I/O 错误"));
  expect(host.textContent).not.toContain("secret");
  expect(host.querySelector("dl")).toBeNull();
});

  it("业务布局明确只读瓦片浏览，目录选择阶段只允许由对话框取消", async () => {
  let resolveSelection!: (value: unknown) => void;
  vi.mocked(invoke)
    .mockResolvedValueOnce(status)
    .mockReturnValueOnce(new Promise((done) => { resolveSelection = done; }));
  const host = mount();
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  expect(host.textContent).toContain("只读 · 主世界");
    expect(host.textContent).toContain("二维地图可自由平移并按视口缩放加载");
  const workspace = host.querySelector(".workspace");
  const backendStatus = host.querySelector(".backend-status");
  expect(workspace).not.toBeNull();
  expect(backendStatus).not.toBeNull();
  expect(workspace!.compareDocumentPosition(backendStatus!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "选择世界目录")!.click();
  await vi.waitFor(() => expect(host.textContent).toContain("正在选择并验证世界目录"));
  expect(host.textContent).not.toContain("取消当前任务");
  resolveSelection(null);
  await vi.waitFor(() => expect(host.textContent).toContain("未选择新的世界"));
});

it.each(["resolve", "reject"])("卸载后忽略迟到的 %s，不影响新实例", async (outcome) => {
  let resolve!: (value: unknown) => void;
  let reject!: (value: unknown) => void;
  vi.mocked(invoke).mockReturnValueOnce(new Promise((done, fail) => { resolve = done; reject = fail; }));
  const old = mount();
  app!.unmount(); app = undefined;
  vi.mocked(invoke).mockResolvedValue(status);
  const current = mount();
  await vi.waitFor(() => expect(current.textContent).toContain("后端就绪"));
  if (outcome === "resolve") resolve({ ...status, appVersion: "stale" });
  else reject(new Error("stale"));
  await new Promise((done) => setTimeout(done, 0));
  await nextTick();
  expect(old.textContent).toBe("");
  expect(current.textContent).not.toContain("stale");
  expect(current.textContent).toContain("后端就绪");
});

it.each(["resolve", "reject"])("卸载后忽略迟到的视口启动 %s", async (outcome) => {
  let resolve!: (value: unknown) => void;
  let reject!: (value: unknown) => void;
  vi.mocked(invoke)
    .mockResolvedValueOnce(status)
    .mockResolvedValueOnce(world)
    .mockReturnValueOnce(new Promise((done, fail) => { resolve = done; reject = fail; }))
    .mockImplementation(async command => command === "cancel_map_viewport" ? null : { cancelled: true });
  const host = mount();
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  Array.from(host.querySelectorAll("button")).find(button => button.textContent === "选择世界目录")!.click();
  await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith("start_map_viewport", {
    sessionId: 1, centerX: 12, centerZ: -8, viewportWidth: 800, viewportHeight: 520, blocksPerPixel: 2, displayBlocksPerPixel: 2,
  }));

  app!.unmount();
  app = undefined;
  if (outcome === "resolve") resolve({ sessionId: 1, viewportTaskId: 1, blocksPerPixel: 2, targetCount: 1, targets: [{ tileX: 0, tileZ: 0, blocksPerPixel: 2 }] });
  else reject({ code: "SESSION_OUTDATED" });
  await new Promise((done) => setTimeout(done, 0));
  await nextTick();
  expect(host.textContent).toBe("");
  expect(invoke).toHaveBeenCalledWith("cancel_active_task");
  if (outcome === "resolve") expect(invoke).toHaveBeenCalledWith("cancel_map_viewport", { sessionId: 1, viewportTaskId: 1 });
});
