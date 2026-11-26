# 代码审查：UpdateCheckClient.java

- **审查日期**：2026-11-26
- **审查范围**：Gitee OpenAPI V5 响应字段兼容、GitHub 回退接口限流规避与系统代理/直连双通道连接
- **问题总数**：2 个（🔴 0 / 🟠 1 / 🟡 1 / 🟢 0）

### ISSUE-UPDATECLIENT-001：Gitee OpenAPI V5 `releases/latest` 响应不含 `html_url` 字段导致版本页回退为列表页

- **严重程度**：🟡 中
- **类别**：模块耦合 / 外部协议兼容
- **文件**：`src/main/java/com/mcworldexplorer/update/UpdateCheckClient.java`
- **行号**：192–199（审查时快照）
- **状态**：已修复

**问题描述**：
Gitee OpenAPI V5 的 `releases/latest` JSON 响应包含 `id`、`tag_name`、`target_commitish`、`prerelease`、`name`、`body`、`author`、`created_at`、`assets`，不包含 GitHub 风格的 `html_url` 字段；原 `parseReleaseJson` 仅读取 `html_url`，在真实 Gitee 响应下始终为空并回退至 `/releases` 列表页。

**当前代码**（审查时快照）：
```java
String htmlUrl = stringField(obj, "html_url");
String giteeUrl = source == Source.GITEE
        ? AllowedExternalUrls.sanitizeRepoReleaseUrl(htmlUrl, "gitee.com", AllowedExternalUrls.GITEE_RELEASES_URL)
        : AllowedExternalUrls.GITEE_RELEASES_URL;
```

**问题分析**：
`tag_name` 已提前通过 `AppVersion.parse` 严格校验为合法版本号格式，当响应缺少 `html_url` 时可直接构造受控同仓 `/releases/tag/<tag_name>` 地址，跨源默认回退地址统一使用直达最新版的 `/releases/latest`。

**建议修改**：
在 `html_url` 为空时按已校验的 `tagName` 构造同仓 `/releases/tag/<tagName>` 候选地址，并将双源默认回退地址更新为 `GITEE_LATEST_RELEASE_URL` 与 `GITHUB_LATEST_RELEASE_URL`。

**影响范围**：
Gitee 主更新源命中新版本时的目标 Release 链接解析及跨源默认最新 Release 地址。

- **解决日期**：2026-11-26
- **实际修改**：更新 `AllowedExternalUrls` 新增 `GITEE_LATEST_RELEASE_URL` / `GITHUB_LATEST_RELEASE_URL`，并在 `UpdateCheckClient.parseReleaseJson` 补入无 `html_url` 时的受控标签页地址构造。
- **验证证据**：`UpdateCheckClientTest.prefersGiteeWhenAvailableAndStripsNetdiskText` 验证通过。

---

### ISSUE-UPDATECLIENT-002：GitHub 回退接口受未认证 REST IP 限流（HTTP 403）且 JVM 未启用系统代理导致检查更新报无法连接

- **严重程度**：🟠 高
- **类别**：错误处理 / 网络连通性
- **文件**：`src/main/java/com/mcworldexplorer/update/UpdateCheckClient.java`
- **行号**：31–33、100–119（审查时快照）
- **状态**：已修复

**问题描述**：
通过 `run-app.bat` 实机测试点击「检查更新」时提示「暂时无法连接到更新源」：一方面 Gitee 镜像仓尚未发布首个 Release 返回 `404` 触发 GitHub 回退；另一方面 JVM 默认 `java.net.useSystemProxies=false` 未读取 Windows 系统代理，且即便经过共享代理出口访问 `https://api.github.com/repos/.../releases/latest` 也会因 GitHub 未认证 REST 接口每小时 60 次 IP 限额直接返回 `HTTP 403 Forbidden`。

**当前代码**（审查时快照）：
```java
public static final URI GITHUB_LATEST_API =
        URI.create("https://api.github.com/repos/huang3337/MC-World-Explorer/releases/latest");
...
HttpClient client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
```

**问题分析**：
1. `https://github.com/huang3337/MC-World-Explorer/releases/latest` 在携带 `Accept: application/json` 请求头时由 GitHub Web 层直接返回轻量 JSON（含 `tag_name`），不受 `api.github.com` 匿名 IP 60 次/小时限流影响。
2. Windows 下 OpenJDK 需在启动阶段开启 `-Djava.net.useSystemProxies=true` 方能识别系统代理；同时国内访问 Gitee 应优先走 `NO_PROXY` 直连以避免本地代理规则干扰，访问 GitHub 则优先走系统代理并在本地失效代理端口拒绝连接时回退直连。

**建议修改**：
将 `GITHUB_LATEST_API` 切换至 `https://github.com/huang3337/MC-World-Explorer/releases/latest`（`Accept: application/json`）；在 `Launcher`、`run-app.bat`、`build.gradle` 与 `tools/package-windows.ps1` 默认启用 `-Djava.net.useSystemProxies=true`，并在 `UpdateCheckClient` 实现 Gitee 直连优先、GitHub 系统代理优先的双路容错请求。

**影响范围**：
开发运行与 Windows 便携包的启动静默检查及「关于」界面手动检查更新连通性。

- **解决日期**：2026-11-26
- **实际修改**：切换 `GITHUB_LATEST_API` 为 Web JSON 端点，补齐 JVM 系统代理开关与 `fetchReleaseWithProxyFallback` 直连/代理双路回退。
- **验证证据**：实机运行编译产物 `UpdateCheckClient` 验证 Gitee `404` 时自动回退 GitHub 成功识别线上 `v0.7.5` Release，全量离线回归通过。
