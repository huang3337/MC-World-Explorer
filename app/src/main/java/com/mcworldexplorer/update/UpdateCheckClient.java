package com.mcworldexplorer.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only release checker that queries Gitee first and silently falls back to GitHub
 * when Gitee times out, fails, or has no published release yet.
 */
public final class UpdateCheckClient {
    public static final URI GITEE_LATEST_API =
            URI.create("https://gitee.com/api/v5/repos/huang3337/MC-World-Explorer/releases/latest");
    /**
     * Uses github.com web JSON endpoint (Accept: application/json) rather than api.github.com
     * so shared proxy/NAT IPs do not fail with HTTP 403 unauthenticated REST rate limits.
     */
    public static final URI GITHUB_LATEST_API =
            URI.create("https://github.com/huang3337/MC-World-Explorer/releases/latest");

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(4);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    public static final int MAX_JSON_DEPTH = 16;

    private static final int MAX_SUMMARY_CHARS = 220;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    public enum Source {
        GITEE,
        GITHUB
    }

    public enum Status {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        FAILED
    }

    public record ReleaseInfo(
            AppVersion version,
            String tagName,
            String title,
            String summary,
            String giteeUrl,
            String githubUrl,
            Source source) {
    }

    public record CheckResult(Status status, ReleaseInfo release, String message) {
        public static CheckResult updateAvailable(ReleaseInfo release) {
            return new CheckResult(Status.UPDATE_AVAILABLE, Objects.requireNonNull(release), "");
        }

        public static CheckResult upToDate(ReleaseInfo release) {
            return new CheckResult(Status.UP_TO_DATE, Objects.requireNonNull(release), "当前已是最新版本。");
        }

        public static CheckResult failed(String message) {
            return new CheckResult(Status.FAILED, null, message == null ? "无法连接更新源，请稍后重试。" : message);
        }
    }

    private final URI primaryEndpoint;
    private final URI fallbackEndpoint;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxResponseBytes;

    public UpdateCheckClient() {
        this(GITEE_LATEST_API, GITHUB_LATEST_API, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, MAX_RESPONSE_BYTES);
    }

    public UpdateCheckClient(URI primaryEndpoint, URI fallbackEndpoint, Duration connectTimeout, Duration readTimeout) {
        this(primaryEndpoint, fallbackEndpoint, connectTimeout, readTimeout, MAX_RESPONSE_BYTES);
    }

    UpdateCheckClient(URI primaryEndpoint, URI fallbackEndpoint,
                      Duration connectTimeout, Duration readTimeout, int maxResponseBytes) {
        this.primaryEndpoint = Objects.requireNonNull(primaryEndpoint, "primaryEndpoint");
        this.fallbackEndpoint = Objects.requireNonNull(fallbackEndpoint, "fallbackEndpoint");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
    }

    public CheckResult checkForUpdate(AppVersion currentVersion) {
        Objects.requireNonNull(currentVersion, "currentVersion");
        Optional<ReleaseInfo> release = fetchReleaseWithProxyFallback(primaryEndpoint, Source.GITEE);
        if (release.isEmpty()) {
            release = fetchReleaseWithProxyFallback(fallbackEndpoint, Source.GITHUB);
        }
        if (release.isEmpty()) {
            return CheckResult.failed("暂时无法连接到更新源，您可以直接打开仓库页面查看最新版本。");
        }
        ReleaseInfo latest = release.get();
        if (latest.version().isNewerThan(currentVersion)) {
            return CheckResult.updateAvailable(latest);
        }
        return CheckResult.upToDate(latest);
    }

    private Optional<ReleaseInfo> fetchReleaseWithProxyFallback(URI endpoint, Source source) {
        if (isLoopbackEndpoint(endpoint)) {
            return fetchRelease(buildClient(HttpClient.Builder.NO_PROXY), endpoint, source).release();
        }
        ProxySelector systemProxy = ProxySelector.getDefault();
        boolean hasSystemProxy = systemProxy != null;
        // Gitee is domestic: prefer direct connection first so local proxy rules cannot break it;
        // GitHub prefers system proxy first when configured, falling back to direct if a stale proxy port is closed.
        List<ProxySelector> candidates = (source == Source.GITEE || !hasSystemProxy)
                ? (hasSystemProxy ? List.of(HttpClient.Builder.NO_PROXY, systemProxy) : List.of(HttpClient.Builder.NO_PROXY))
                : List.of(systemProxy, HttpClient.Builder.NO_PROXY);

        for (ProxySelector selector : candidates) {
            FetchAttempt attempt = fetchRelease(buildClient(selector), endpoint, source);
            if (attempt.release().isPresent()) {
                return attempt.release();
            }
            // Do not repeat the same HTTP 404/200 response over a second route if the server already answered cleanly.
            if (attempt.serverResponded()) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private HttpClient buildClient(ProxySelector selector) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(selector)
                .build();
    }

    private static boolean isLoopbackEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        return host != null && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host));
    }

    private record FetchAttempt(Optional<ReleaseInfo> release, boolean serverResponded) { }

    private FetchAttempt fetchRelease(HttpClient client, URI endpoint, Source source) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "MC-World-Explorer/" + AppVersion.currentVersionString())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int code = response.statusCode();
                if (code != 200) {
                    // 404 means repository has no published releases yet (definitive server answer);
                    // 403/5xx may be proxy-specific, allow alternate route if available.
                    return new FetchAttempt(Optional.empty(), code == 404);
                }
                String json = readBoundedUtf8(body, maxResponseBytes);
                if (json == null || json.isBlank()) {
                    return new FetchAttempt(Optional.empty(), true);
                }
                return new FetchAttempt(parseReleaseJson(json, source), true);
            }
        } catch (IOException | RuntimeException failure) {
            return new FetchAttempt(Optional.empty(), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new FetchAttempt(Optional.empty(), true);
        }
    }

    static String readBoundedUtf8(InputStream input, int limitBytes) throws IOException {
        if (input == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > limitBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    static Optional<ReleaseInfo> parseReleaseJson(String json, Source source) {
        if (json == null || json.isBlank() || !withinMaxDepth(json, MAX_JSON_DEPTH)) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.isEmpty()) {
                return Optional.empty();
            }
            if (booleanField(obj, "draft") || booleanField(obj, "prerelease")) {
                return Optional.empty();
            }
            String tagName = stringField(obj, "tag_name");
            Optional<AppVersion> version = AppVersion.parse(tagName);
            if (version.isEmpty() || version.get().snapshot()) {
                return Optional.empty();
            }
            String title = stringField(obj, "name");
            if (title.isEmpty()) {
                title = tagName;
            }
            String summary = sanitizeSummary(stringField(obj, "body"));
            String htmlUrl = stringField(obj, "html_url");
            String giteeCandidate = !htmlUrl.isEmpty()
                    ? htmlUrl
                    : "https://gitee.com/huang3337/MC-World-Explorer/releases/tag/" + tagName;
            String githubCandidate = !htmlUrl.isEmpty()
                    ? htmlUrl
                    : "https://github.com/huang3337/MC-World-Explorer/releases/tag/" + tagName;
            String giteeUrl = source == Source.GITEE
                    ? AllowedExternalUrls.sanitizeRepoReleaseUrl(
                            giteeCandidate, "gitee.com", AllowedExternalUrls.GITEE_LATEST_RELEASE_URL)
                    : AllowedExternalUrls.GITEE_LATEST_RELEASE_URL;
            String githubUrl = source == Source.GITHUB
                    ? AllowedExternalUrls.sanitizeRepoReleaseUrl(
                            githubCandidate, "github.com", AllowedExternalUrls.GITHUB_LATEST_RELEASE_URL)
                    : AllowedExternalUrls.GITHUB_LATEST_RELEASE_URL;
            return Optional.of(new ReleaseInfo(version.get(), tagName, title, summary, giteeUrl, githubUrl, source));
        } catch (JsonParseException | IllegalStateException invalid) {
            return Optional.empty();
        }
    }

    static boolean withinMaxDepth(String json, int maxDepth) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            int depth = 0;
            while (true) {
                JsonToken token = reader.peek();
                switch (token) {
                    case BEGIN_OBJECT -> {
                        depth++;
                        if (depth > maxDepth) return false;
                        reader.beginObject();
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case BEGIN_ARRAY -> {
                        depth++;
                        if (depth > maxDepth) return false;
                        reader.beginArray();
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case NAME -> reader.nextName();
                    case STRING -> reader.nextString();
                    case NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    case END_DOCUMENT -> {
                        return true;
                    }
                }
            }
        } catch (IOException | IllegalStateException invalid) {
            return false;
        }
    }

    private static boolean booleanField(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
    }

    private static String stringField(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return "";
        }
        return el.getAsString().strip();
    }

    private static String sanitizeSummary(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            // Strip markdown headings/bullets and never display external download links in summary.
            String cleaned = trimmed.replaceFirst("^#+\\s*", "").replaceFirst("^[-*]\\s+", "");
            cleaned = URL_PATTERN.matcher(cleaned).replaceAll("").strip();
            if (cleaned.isEmpty() || cleaned.contains("网盘") || cleaned.toLowerCase().contains("quark")) {
                continue;
            }
            if (!out.isEmpty()) out.append('\n');
            out.append(cleaned);
            if (out.length() >= MAX_SUMMARY_CHARS) {
                break;
            }
        }
        String result = out.toString().strip();
        if (result.length() > MAX_SUMMARY_CHARS) {
            return result.substring(0, MAX_SUMMARY_CHARS - 1).stripTrailing() + "…";
        }
        return result;
    }
}
