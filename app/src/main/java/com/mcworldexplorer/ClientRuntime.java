package com.mcworldexplorer;

import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.storage.RuntimePreferences;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ClientRuntime {
    private static final String CONFIGURED = "--mcwe-configured-runtime";
    private static final String WAIT_FOR = "--mcwe-restart-after=";
    private static final String GPU_SESSION = "MCWE_GPU_SESSION";
    private static final String HEAP_SUFFIX = "MCWE_PACKAGED_HEAP_OPTION";
    private static String startupNotice = "";
    private static boolean externalMapPipeline;
    private static volatile String gpuSession;
    private static RuntimePreferences startupPrefs;
    private static List<String> applicationArgs = List.of();
    private ClientRuntime() {}

    public static String startupNotice() { return startupNotice; }
    public static boolean externalMapPipeline() { return externalMapPipeline; }
    public static boolean restartNeeded(RuntimePreferences selected) {
        return startupPrefs != null && (selected.heapMiB() != startupPrefs.heapMiB()
                || selected.mapMode() != startupPrefs.mapMode() || !selected.mapAdapter().equals(startupPrefs.mapAdapter()));
    }

    public static String executable() {
        return ProcessHandle.current().info().command().orElseGet(() ->
                Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    /** Return true when another JVM owns the client. All choices precede toolkit initialization. */
    static boolean prepare(String[] args) {
        externalMapPipeline = System.getProperty("prism.order") != null;
        boolean configured = List.of(args).contains(CONFIGURED);
        if (configured && WindowsGraphics.supported()) gpuSession = System.getenv(GPU_SESSION);
        for (String arg : args) {
            if (arg.startsWith(WAIT_FOR)) {
                try {
                    var previous = ProcessHandle.of(Long.parseLong(arg.substring(WAIT_FOR.length())));
                    if (previous.isPresent()) previous.get().onExit().get(90, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    notice("上一次 MCWE 尚未完成退出：" + failure.getMessage());
                    return true;
                }
            }
        }
        applicationArgs = List.of(args).stream().filter(a -> !a.startsWith(WAIT_FOR) && !a.equals(CONFIGURED)).toList();
        RuntimePreferences prefs;
        PortableSettings store = new PortableSettings();
        try {
            startupPrefs = prefs = store.loadRuntimePreferences();
        } catch (IOException failure) {
            notice("运行设置读取失败，使用默认设置：" + failure.getMessage());
            return false;
        }
        long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        boolean changeHeap = prefs.heapMiB() != 0 && Math.abs(maxHeap - prefs.heapMiB()) > 1;
        boolean gpuChanged = false;
        boolean gpuAcquired = false;
        try {
            if (WindowsGraphics.supported()) {
                if (prefs.requestsGpu()) {
                    if (gpuSession == null) gpuSession = java.util.UUID.randomUUID().toString();
                    boolean legacy = store.hasLegacyGpuPreference();
                    gpuChanged = WindowsGraphics.acquire(prefs, gpuSession, legacy);
                    gpuAcquired = true;
                    if (legacy) store.markGpuSessionPreference(prefs);
                } else if (store.gpuResetPending(executable())) {
                    gpuChanged = WindowsGraphics.clearLegacyPreference();
                    store.finishGpuReset(executable());
                }
            }
            else if (prefs.requestsGpu()) {
                notice("请使用 run-app.bat 或便携包以应用显卡偏好，此次使用系统设备。");
            }
        } catch (IOException failure) {
            notice("无法应用 Windows 显卡偏好：" + failure.getMessage());
        }
        if (configured) {
            if (gpuChanged) notice("Windows 显卡偏好发生变化，请重启 MCWE 核对实际设备。");
            if (changeHeap) notice("当前 JVM 采用 " + maxHeap + " MiB 最大堆，启动参数或垃圾收集器影响了实际容量。");
        } else if (changeHeap || gpuChanged || gpuAcquired) {
            // Another launcher may have set the preference after this process was created.
            // Start the renderer while our own GPU session still holds it.
            try {
                if (launchConfigured(prefs.heapMiB())) {
                    gpuSession = null; // The rendering JVM now owns this session.
                    return true;
                }
                notice("新进程启动失败，使用当前进程的渲染设备与堆容量。");
            } catch (IOException failure) {
                notice("无法应用启动设置：" + failure.getMessage());
            }
        }
        if (prefs.softwareMap() && !externalMapPipeline) {
            String hardware = System.getProperty("os.name", "").startsWith("Windows") ? "d3d" : "es2";
            System.setProperty("prism.order", "sw," + hardware);
        }
        return false;
    }

    public static void restart(RuntimePreferences selected) throws IOException {
        var builder = clientBuilder(selected.heapMiB());
        builder.command().add(WAIT_FOR + ProcessHandle.current().pid());
        builder.start();
    }

    static boolean awaitingMapAdapter() {
        return gpuSession != null && startupPrefs != null && !startupPrefs.mapAdapter().isEmpty();
    }

    static void confirmMapAdapter(String actualId) {
        if (awaitingMapAdapter() && startupPrefs.mapAdapter().equals(actualId)) {
            Thread.startVirtualThread(ClientRuntime::releaseGpu);
        }
    }

    static synchronized void releaseGpu() {
        if (gpuSession == null) return;
        try {
            WindowsGraphics.release(gpuSession);
            gpuSession = null;
        }
        catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(ClientRuntime.class).warn("无法恢复 Windows 显卡偏好", failure);
        }
    }

    private static ProcessBuilder clientBuilder(int heapMiB) throws IOException {
        String packaged = System.getProperty("jpackage.app-path");
        var command = new ArrayList<String>();
        command.add(packaged == null ? executable() : packaged);
        if (packaged == null) {
            // A debugger owns a socket in this JVM and cannot be attached a second time.
            if (ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .anyMatch(arg -> arg.startsWith("-agentlib:jdwp") || arg.startsWith("-Xrunjdwp"))) {
                throw new IOException("调试会话请在启动配置中设置 -Xmx");
            }
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) continue;
                command.add(arg);
            }
            if (heapMiB > 0) command.add("-Xmx" + heapMiB + "m");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Launcher.class.getName());
        }
        command.addAll(applicationArgs);
        var builder = new ProcessBuilder(command).inheritIO();
        builder.environment().remove(GPU_SESSION);
        if (packaged == null) {
            // getInputArguments already includes options supplied by these environment variables.
            for (String name : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
                builder.environment().remove(name);
        }
        if (packaged != null) {
            // jpackage creates the VM directly. Its executable identity also owns the Windows GPU preference.
            var env = builder.environment();
            String previous = env.remove(HEAP_SUFFIX);
            String options = env.getOrDefault("JAVA_TOOL_OPTIONS", "");
            if (previous != null && options.endsWith(previous)) options = options.substring(0, options.length() - previous.length()).stripTrailing();
            if (heapMiB > 0) {
                String suffix = "-Xmx" + heapMiB + "m";
                options = (options + " " + suffix).strip();
                env.put(HEAP_SUFFIX, suffix);
            }
            if (options.isEmpty()) env.remove("JAVA_TOOL_OPTIONS"); else env.put("JAVA_TOOL_OPTIONS", options);
        }
        return builder;
    }

    private static boolean launchConfigured(int heapMiB) throws IOException {
        var builder = clientBuilder(heapMiB);
        builder.command().add(CONFIGURED);
        if (gpuSession != null) builder.environment().put(GPU_SESSION, gpuSession);
        var client = builder.start();
        try {
            if (client.waitFor(2, TimeUnit.SECONDS)) return client.exitValue() == 0;
            if (System.getProperty("jpackage.app-path") != null || WindowsGraphics.supported()) return true;
            Thread stop = new Thread(client::destroy, "client-launcher-shutdown");
            Runtime.getRuntime().addShutdownHook(stop);
            try { client.waitFor(); return true; }
            finally { Runtime.getRuntime().removeShutdownHook(stop); }
        } catch (InterruptedException interrupted) {
            client.destroy();
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private static void notice(String message) {
        startupNotice = startupNotice.isEmpty() ? message : startupNotice + "\n" + message;
        System.err.println(message);
    }
}
