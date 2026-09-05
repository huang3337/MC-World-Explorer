import { afterEach, vi } from "vitest";

// 测试必须显式提供 IPC 结果，不能回落到真实 Tauri。
vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

afterEach(() => {
  vi.resetAllMocks();
  document.body.replaceChildren();
});
