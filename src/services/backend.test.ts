import { describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { getAppStatus, toDisplayError } from "./backend";

const status = {
  appVersion: "0.7.1", backendStatus: "ready",
  portablePaths: { root: "D:\\MCWE", cache: "D:\\MCWE\\cache", logs: "D:\\MCWE\\logs", exports: "D:\\MCWE\\exports", config: "D:\\MCWE\\config" },
};

describe("backend", () => {
  it("只通过 app_status 获取无参数状态", async () => {
    vi.mocked(invoke).mockResolvedValue(status);
    expect(await getAppStatus()).toEqual(status);
    expect(invoke).toHaveBeenCalledExactlyOnceWith("app_status");
  });

  it.each([null, {}, { ...status, backendStatus: "unknown" }, { ...status, portablePaths: { root: "D:\\" } }])("拒绝不完整或未知成功响应 %j", async (value) => {
    vi.mocked(invoke).mockResolvedValue(value);
    await expect(getAppStatus()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("后端错误只显示固定中文，不信任附带的内部文本", async () => {
    vi.mocked(invoke).mockRejectedValue({ code: "PERMISSION_DENIED", message: "C:\\secret stack", context: "READ_WORLD" });
    await expect(getAppStatus()).rejects.toEqual({ code: "PERMISSION_DENIED", message: "没有读取来源的权限", context: "READ_WORLD" });
  });

  it.each([new Error("C:\\secret"), "internal stack", null, { code: "toString", message: "secret" }])("未知异常安全降级 %j", (value) => {
    expect(toDisplayError(value)).toEqual({ code: "IPC_ERROR", message: "无法连接后端，请在桌面应用中重试" });
  });
});
