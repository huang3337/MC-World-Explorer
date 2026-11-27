package com.mcworldexplorer;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.storage.RuntimePreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Per-user Windows preferences are keyed by the exact rendering process executable. */
public final class WindowsGraphics {
    private static List<Adapter> recentAdapters = List.of();
    private static long lastScan;
    private WindowsGraphics() {}
    public static boolean supported() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
        Path current = Path.of(ClientRuntime.executable()).toAbsolutePath().normalize();
        String name = current.getFileName().toString();
        if (name.equalsIgnoreCase("java.exe") || name.equalsIgnoreCase("javaw.exe")) return false;
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && current.equals(Path.of(packaged).toAbsolutePath().normalize())) return true;
        String project = System.getProperty("mcwe.projectDir");
        return project != null && current.equals(Path.of(project).toAbsolutePath().normalize()
                .resolve("build/windows-client/runtime/bin/MCWE.exe"));
    }

    static boolean acquire(RuntimePreferences prefs, String session, boolean legacy) throws IOException {
        String operation = !prefs.mapAdapter().isEmpty() ? prefs.mapAdapter() : switch (prefs.mapMode()) {
            case POWER -> "power";
            case PERFORMANCE -> "performance";
            default -> throw new IllegalArgumentException("缺少硬件偏好");
        };
        return preference(operation, session, legacy);
    }

    static void release(String session) throws IOException { preference("release", session, false); }

    static boolean clearLegacyPreference() throws IOException { return preference("reset", "", false); }

    private static boolean preference(String operation, String session, boolean legacy) throws IOException {
        if (!supported()) throw new IOException("此启动入口没有 MCWE 专属显卡偏好");
        return switch (run("graphics.ps1", Map.of(
                "MCWE_GRAPHICS_ACTION", operation, "MCWE_GRAPHICS_EXE", ClientRuntime.executable(),
                "MCWE_GRAPHICS_CACHE", PortablePaths.cacheDirectory().toString(),
                "MCWE_GRAPHICS_SESSION", session, "MCWE_GRAPHICS_PID", Long.toString(ProcessHandle.current().pid()),
                "MCWE_GRAPHICS_LEGACY", Boolean.toString(legacy)))) {
            case "changed" -> true;
            case "unchanged" -> false;
            default -> throw new IOException("Windows 未确认显卡偏好");
        };
    }

    public record Adapter(String id, String name) {
        @Override public String toString() { return name; }
    }

    public static synchronized List<Adapter> adapters() throws IOException {
        if (!recentAdapters.isEmpty() && System.nanoTime() - lastScan < TimeUnit.SECONDS.toNanos(30)) return recentAdapters;
        var models = new java.util.LinkedHashMap<String, Adapter>();
        for (String row : run("display-adapters.ps1", Map.of()).lines().toList()) {
            String[] parts = row.split("\t", 2);
            if (parts.length == 2 && RuntimePreferences.validAdapter(parts[0]) && !parts[1].isBlank()) {
                models.putIfAbsent(parts[0], new Adapter(parts[0], parts[1]));
            }
        }
        lastScan = System.nanoTime();
        recentAdapters = List.copyOf(models.values());
        return recentAdapters;
    }

    private static String run(String resource, Map<String, String> environment) throws IOException {
        String script;
        try (var input = WindowsGraphics.class.getResourceAsStream("/windows/" + resource)) {
            if (input == null) throw new IOException("缺少 Windows 显卡查询组件");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path shell = Path.of(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe");
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        var builder = new ProcessBuilder(shell.toString(), "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-EncodedCommand", encoded);
        builder.environment().putAll(environment);
        var process = builder.redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Windows 显卡操作超时");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IOException(output.isEmpty() ? "Windows 显卡查询失败" : output);
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Windows 显卡查询已中止", interrupted);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
