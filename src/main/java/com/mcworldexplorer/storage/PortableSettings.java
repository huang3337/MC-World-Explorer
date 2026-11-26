package com.mcworldexplorer.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

public final class PortableSettings {
    private static final String SETTINGS_FILE_NAME = "settings.properties";
    private static final String CUSTOM_SAVES_PATH = "custom_saves_path";
    private static final String VIEWER_PERFORMANCE_PROFILE = "viewer_performance_profile";
    private static final String APPEARANCE = "appearance";
    private static final String CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup";
    private static final String LAST_UPDATE_CHECK_EPOCH_MS = "last_update_check_epoch_ms";
    private static final String IGNORED_UPDATE_VERSION = "ignored_update_version";
    private static final String FIRST_RUN_COMPLETED = "first_run_completed";

    public static final long UPDATE_CHECK_COOLDOWN_MS = 12L * 60L * 60L * 1000L;

    public RuntimePreferences loadRuntimePreferences() throws IOException {
        return RuntimePreferences.read(loadProperties());
    }

    public boolean gpuResetPending(String executable) throws IOException {
        return gpuResetPending(loadProperties(), executable);
    }

    private static boolean gpuResetPending(Properties values, String executable) {
        String target = values.getProperty("map_gpu_reset");
        return "true".equals(target) || executable.equalsIgnoreCase(target);
    }

    public boolean hasLegacyGpuPreference() throws IOException {
        var values = loadProperties();
        return RuntimePreferences.read(values).requestsGpu() && !"session".equals(values.getProperty("map_gpu_scope"));
    }

    public void markGpuSessionPreference(RuntimePreferences selected) throws IOException {
        updateIf(values -> {
            if (!selected.equals(RuntimePreferences.read(values)) || "session".equals(values.getProperty("map_gpu_scope"))) return false;
            values.setProperty("map_gpu_scope", "session");
            return true;
        });
    }

    public void finishGpuReset(String executable) throws IOException {
        updateIf(values -> gpuResetPending(values, executable) && values.remove("map_gpu_reset") != null);
    }

    public void savePreferences(RuntimePreferences runtime, boolean dark, String profile, Path changedRoot)
            throws IOException {
        savePreferences(runtime, dark, profile, changedRoot, null);
    }

    public void savePreferences(RuntimePreferences runtime, boolean dark, String profile, Path changedRoot,
                                Boolean checkUpdatesOnStartup) throws IOException {
        update(values -> {
            runtime.write(values);
            values.setProperty(APPEARANCE, dark ? "dark" : "light");
            values.setProperty(VIEWER_PERFORMANCE_PROFILE, profile);
            if (changedRoot != null) values.setProperty(CUSTOM_SAVES_PATH, changedRoot.toAbsolutePath().normalize().toString());
            if (checkUpdatesOnStartup != null) {
                values.setProperty(CHECK_UPDATES_ON_STARTUP, Boolean.toString(checkUpdatesOnStartup));
            }
        });
    }

    public boolean isCheckUpdatesOnStartup() throws IOException {
        String value = loadProperties().getProperty(CHECK_UPDATES_ON_STARTUP);
        return value == null || !"false".equalsIgnoreCase(value.strip());
    }

    public void saveCheckUpdatesOnStartup(boolean enabled) throws IOException {
        update(values -> values.setProperty(CHECK_UPDATES_ON_STARTUP, Boolean.toString(enabled)));
    }

    public long loadLastUpdateCheckEpochMs() throws IOException {
        String value = loadProperties().getProperty(LAST_UPDATE_CHECK_EPOCH_MS);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.strip()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public void saveLastUpdateCheckEpochMs(long epochMs) throws IOException {
        update(values -> values.setProperty(LAST_UPDATE_CHECK_EPOCH_MS, Long.toString(Math.max(0L, epochMs))));
    }

    public String loadIgnoredUpdateVersion() throws IOException {
        String value = loadProperties().getProperty(IGNORED_UPDATE_VERSION);
        return value == null ? "" : value.strip();
    }

    public void saveIgnoredUpdateVersion(String version) throws IOException {
        update(values -> {
            if (version == null || version.isBlank()) {
                values.remove(IGNORED_UPDATE_VERSION);
            } else {
                values.setProperty(IGNORED_UPDATE_VERSION, version.strip());
            }
        });
    }

    public boolean shouldShowFirstRunImport() throws IOException {
        return shouldShowFirstRunImport(PortablePaths.applicationRoot());
    }

    public static boolean shouldShowFirstRunImport(Path appRoot) throws IOException {
        if ("true".equalsIgnoreCase(System.getProperty("mcwe.skipFirstRunImport"))) {
            return false;
        }
        if (appRoot == null) {
            return false;
        }
        Path normalized = appRoot.toAbsolutePath().normalize();
        // Exclude development project root checkouts from triggering the fresh-package prompt.
        if (Files.isRegularFile(normalized.resolve("build.gradle"))
                && Files.isRegularFile(normalized.resolve("settings.gradle"))) {
            return false;
        }
        Path file = normalized.resolve("config").resolve(SETTINGS_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return true;
        }
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            props.load(input);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return "false".equalsIgnoreCase(props.getProperty(FIRST_RUN_COMPLETED, "").strip());
    }

    public void markFirstRunCompleted() throws IOException {
        update(values -> values.setProperty(FIRST_RUN_COMPLETED, "true"));
    }

    public boolean usesDarkTheme() throws IOException {
        return "dark".equals(loadProperties().getProperty(APPEARANCE));
    }

    public void saveDarkTheme(boolean dark) throws IOException {
        update(properties -> properties.setProperty(APPEARANCE, dark ? "dark" : "light"));
    }

    public Optional<Path> loadCustomSavesPath() throws IOException {
        Path settingsFile = settingsFile();
        if (!Files.isRegularFile(settingsFile)) {
            return Optional.empty();
        }

        Properties properties = loadProperties();
        String configuredPath = properties.getProperty(CUSTOM_SAVES_PATH);
        if (configuredPath == null || configuredPath.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(configuredPath).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            throw new IOException("本地配置中的 Minecraft 目录无效", e);
        }
    }

    public synchronized void saveCustomSavesPath(Path selectedPath) throws IOException {
        if (selectedPath == null) {
            throw new IllegalArgumentException("selectedPath must not be null");
        }

        update(properties -> properties.setProperty(
                CUSTOM_SAVES_PATH,
                selectedPath.toAbsolutePath().normalize().toString()));
    }

    public Optional<String> loadViewerPerformanceProfile() throws IOException {
        String value = loadProperties().getProperty(VIEWER_PERFORMANCE_PROFILE);
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(value.trim());
    }

    public synchronized void saveViewerPerformanceProfile(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("viewer performance profile must not be blank");
        }
        update(properties -> properties.setProperty(VIEWER_PERFORMANCE_PROFILE, value.trim()));
    }

    public Optional<Path> loadClientResourcePath(Path world, String version) throws IOException {
        String value = loadProperties().getProperty(resourceKey(world, version));
        if (value == null || value.isBlank()) return Optional.empty();
        try { return Optional.of(Path.of(value).toAbsolutePath().normalize()); }
        catch (InvalidPathException e) { throw new IOException("保存的客户端资源路径无效", e); }
    }

    public synchronized void saveClientResourcePath(Path world, String version, Path path) throws IOException {
        String key = resourceKey(world, version);
        update(properties -> {
            if (path == null) properties.remove(key);
            else properties.setProperty(key, path.toAbsolutePath().normalize().toString());
        });
    }

    public Optional<Path> loadResourceInstancePath(Path world,String version)throws IOException {
        String value=loadProperties().getProperty("instance."+resourceKey(world,version));
        if(value==null||value.isBlank())return Optional.empty();
        try{return Optional.of(Path.of(value).toAbsolutePath().normalize());}catch(InvalidPathException invalid){throw new IOException("资源实例绑定无效",invalid);}
    }
    public synchronized void saveResourceInstancePath(Path world,String version,Path path)throws IOException {
        String key="instance."+resourceKey(world,version);
        update(properties -> {
            if(path==null)properties.remove(key);else properties.setProperty(key,path.toAbsolutePath().normalize().toString());
        });
    }

    /** Reset both choices together, preserving other worlds, versions and preferences. */
    public synchronized void clearResourceBindings(Path world,String version)throws IOException {
        String key=resourceKey(world,version);
        update(properties -> { properties.remove(key);properties.remove("instance."+key); });
    }

    private static String resourceKey(Path world, String version) {
        java.util.Objects.requireNonNull(world, "world");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        String identity = world.toAbsolutePath().normalize() + "\0" + version;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "client_resource." + java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static Path settingsFile() {
        return PortablePaths.configDirectory().resolve(SETTINGS_FILE_NAME);
    }

    private static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        Path settingsFile = settingsFile();
        if (!Files.isRegularFile(settingsFile)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(settingsFile)) {
            try { properties.load(input); }
            catch (IllegalArgumentException invalid) { throw new IOException("本地配置格式无效", invalid); }
        }
        return properties;
    }

    private static void update(java.util.function.Consumer<Properties> change) throws IOException {
        updateIf(values -> { change.accept(values); return true; });
    }

    private static synchronized void updateIf(java.util.function.Predicate<Properties> change) throws IOException {
        Properties properties = loadProperties();
        if (!change.test(properties)) return;
        Path configDirectory = PortablePaths.configDirectory();
        Files.createDirectories(configDirectory);
        Path settingsFile = settingsFile();
        Path temporary = Files.createTempFile(configDirectory, ".settings-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MC World Explorer portable settings");
            }
            replace(temporary, settingsFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
