import { afterEach, expect, it, vi } from "vitest";
import { createApp, nextTick, type App as VueApp } from "vue";
import { invoke } from "@tauri-apps/api/core";
import App from "./App.vue";

let app: VueApp | undefined;
afterEach(() => { app?.unmount(); app = undefined; });

function mount() {
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(App);
  app.mount(host);
  return host;
}

const status = {
  appVersion: "0.7.1", backendStatus: "ready",
  portablePaths: { root: "D:\\便携目录", cache: "D:\\便携目录\\cache", logs: "D:\\便携目录\\logs", exports: "D:\\便携目录\\exports", config: "D:\\便携目录\\config" },
};

it("从加载状态进入就绪，显示版本及五个路径", async () => {
  let resolve!: (value: unknown) => void;
  vi.mocked(invoke).mockReturnValue(new Promise((done) => { resolve = done; }));
  const host = mount();
  expect(host.textContent).toContain("正在连接后端");
  resolve(status);
  await vi.waitFor(() => expect(host.textContent).toContain("后端就绪"));
  expect(host.textContent).toContain("0.7.1");
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
