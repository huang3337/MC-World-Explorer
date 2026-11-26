package com.mcworldexplorer.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckClientTest {

    @Test
    void comparesSemanticVersionsAndSnapshots() {
        AppVersion snap = AppVersion.parse("0.7.9-SNAPSHOT").orElseThrow();
        AppVersion rel = AppVersion.parse("v0.7.9").orElseThrow();
        AppVersion next = AppVersion.parse("0.8.0").orElseThrow();

        assertTrue(rel.isNewerThan(snap));
        assertFalse(snap.isNewerThan(rel));
        assertTrue(next.isNewerThan(rel));
        assertEquals(0, rel.compareTo(AppVersion.parse("0.7.9").orElseThrow()));
        assertTrue(AppVersion.parse("not-a-version").isEmpty());
        assertTrue(AppVersion.parse("0.8.0-beta.1").isEmpty());
    }

    @Test
    void enforcesHttpsAllowlistAndSanitizesReleaseUrls() {
        assertTrue(AllowedExternalUrls.isAllowed("https://gitee.com/huang3337/MC-World-Explorer/releases"));
        assertTrue(AllowedExternalUrls.isAllowed("https://gitee.com/huang3337/MC-World-Explorer/releases/latest"));
        assertTrue(AllowedExternalUrls.isAllowed("https://github.com/huang3337/MC-World-Explorer/releases"));
        assertTrue(AllowedExternalUrls.isAllowed("https://github.com/huang3337/MC-World-Explorer/releases/latest"));
        assertTrue(AllowedExternalUrls.isAllowed("https://space.bilibili.com/424431265"));

        assertFalse(AllowedExternalUrls.isAllowed("http://gitee.com/huang3337/MC-World-Explorer/releases"));
        assertFalse(AllowedExternalUrls.isAllowed("https://pan.quark.cn/s/123456"));
        assertFalse(AllowedExternalUrls.isAllowed("https://example.com/releases"));
        assertThrows(IllegalArgumentException.class, () -> AllowedExternalUrls.requireAllowed("https://pan.quark.cn/s/abc"));

        assertEquals(
                "https://gitee.com/huang3337/MC-World-Explorer/releases/tag/v0.8.0",
                AllowedExternalUrls.sanitizeRepoReleaseUrl(
                        "https://gitee.com/huang3337/MC-World-Explorer/releases/tag/v0.8.0",
                        "gitee.com",
                        AllowedExternalUrls.GITEE_RELEASES_URL));
        assertEquals(
                AllowedExternalUrls.GITEE_RELEASES_URL,
                AllowedExternalUrls.sanitizeRepoReleaseUrl(
                        "https://gitee.com/other/repo/releases/tag/v0.8.0",
                        "gitee.com",
                        AllowedExternalUrls.GITEE_RELEASES_URL));
    }

    @Test
    void prefersGiteeWhenAvailableAndStripsNetdiskText() throws Exception {
        AtomicInteger githubHits = new AtomicInteger();
        try (LoopbackServer server = new LoopbackServer()) {
            server.respond("/gitee", 200, """
                    {
                      "id": 1001,
                      "tag_name": "v0.8.0",
                      "target_commitish": "main",
                      "name": "0.8.0",
                      "body": "### 新特性\\n- 支持 GPU 顶点着色\\n- 夸克网盘：https://pan.quark.cn/s/should-not-appear",
                      "prerelease": false
                    }
                    """);
            server.respond("/github", exchange -> {
                githubHits.incrementAndGet();
                writeJson(exchange, 200, "{}");
            });

            var client = new UpdateCheckClient(
                    server.uri("/gitee"), server.uri("/github"),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            var result = client.checkForUpdate(AppVersion.parse("0.7.9").orElseThrow());

            assertEquals(UpdateCheckClient.Status.UPDATE_AVAILABLE, result.status());
            assertEquals(UpdateCheckClient.Source.GITEE, result.release().source());
            assertEquals("v0.8.0", result.release().tagName());
            assertEquals("https://gitee.com/huang3337/MC-World-Explorer/releases/tag/v0.8.0", result.release().giteeUrl());
            assertEquals(AllowedExternalUrls.GITHUB_LATEST_RELEASE_URL, result.release().githubUrl());
            assertFalse(result.release().summary().contains("quark"));
            assertFalse(result.release().summary().contains("网盘"));
            assertEquals(0, githubHits.get());
        }
    }

    @Test
    void fallsBackToGithubOnGitee404EmptyObjectEmptyArrayOrMissingTag() throws Exception {
        String githubPayload = """
                {
                  "id": 452266565,
                  "tag_name": "v0.8.0",
                  "update_url": "/huang3337/MC-World-Explorer/releases/tag/v0.8.0"
                }
                """;
        for (String[] scenario : new String[][]{
                {"404", "not found"},
                {"200", "{}"},
                {"200", "[]"},
                {"200", "{\"name\":\"empty\"}"},
                {"200", "{\"tag_name\":\"v0.8.0\",\"draft\":true}"},
                {"200", "{\"tag_name\":\"v0.8.0\",\"prerelease\":true}"}
        }) {
            int status = Integer.parseInt(scenario[0]);
            String body = scenario[1];
            try (LoopbackServer server = new LoopbackServer()) {
                server.respond("/gitee", status, body);
                server.respond("/github", 200, githubPayload);

                var client = new UpdateCheckClient(
                        server.uri("/gitee"), server.uri("/github"),
                        Duration.ofSeconds(2), Duration.ofSeconds(2));
                var result = client.checkForUpdate(AppVersion.parse("0.7.9").orElseThrow());

                assertEquals(UpdateCheckClient.Status.UPDATE_AVAILABLE, result.status(), "scenario: " + body);
                assertEquals(UpdateCheckClient.Source.GITHUB, result.release().source());
                assertEquals(AllowedExternalUrls.GITEE_LATEST_RELEASE_URL, result.release().giteeUrl());
                assertEquals("https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.8.0", result.release().githubUrl());
            }
        }
    }

    @Test
    void fallsBackToGithubOnGiteeTimeoutOversizedPayloadAndExcessiveJsonDepth() throws Exception {
        String githubPayload = """
                {
                  "tag_name": "v0.7.9",
                  "name": "0.7.9",
                  "draft": false,
                  "prerelease": false
                }
                """;
        try (LoopbackServer server = new LoopbackServer()) {
            server.respond("/gitee-slow", exchange -> {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                writeJson(exchange, 200, githubPayload);
            });
            server.respond("/gitee-big", 200, "{\"tag_name\":\"v0.8.0\",\"body\":\"" + "a".repeat(70_000) + "\"}");
            server.respond("/gitee-deep", 200, "[".repeat(20) + "]".repeat(20));
            server.respond("/github", 200, githubPayload);

            var slowClient = new UpdateCheckClient(
                    server.uri("/gitee-slow"), server.uri("/github"),
                    Duration.ofMillis(200), Duration.ofMillis(200));
            assertEquals(UpdateCheckClient.Status.UP_TO_DATE,
                    slowClient.checkForUpdate(AppVersion.parse("0.7.9").orElseThrow()).status());

            var bigClient = new UpdateCheckClient(
                    server.uri("/gitee-big"), server.uri("/github"),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            var bigResult = bigClient.checkForUpdate(AppVersion.parse("0.7.9").orElseThrow());
            assertEquals(UpdateCheckClient.Status.UP_TO_DATE, bigResult.status());
            assertEquals(UpdateCheckClient.Source.GITHUB, bigResult.release().source());

            var deepClient = new UpdateCheckClient(
                    server.uri("/gitee-deep"), server.uri("/github"),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            var deepResult = deepClient.checkForUpdate(AppVersion.parse("0.7.9").orElseThrow());
            assertEquals(UpdateCheckClient.Status.UP_TO_DATE, deepResult.status());
            assertEquals(UpdateCheckClient.Source.GITHUB, deepResult.release().source());
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class LoopbackServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        LoopbackServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.setExecutor(executor);
            this.server.start();
        }

        void respond(String path, int status, String body) {
            server.createContext(path, exchange -> writeJson(exchange, status, body));
        }

        void respond(String path, com.sun.net.httpserver.HttpHandler handler) {
            server.createContext(path, handler);
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
