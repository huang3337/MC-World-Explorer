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

        Properties properties = loadProperties();
        properties.setProperty(
                CUSTOM_SAVES_PATH,
                selectedPath.toAbsolutePath().normalize().toString());
        saveProperties(properties);
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
        Properties properties = loadProperties();
        properties.setProperty(VIEWER_PERFORMANCE_PROFILE, value.trim());
        saveProperties(properties);
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
            properties.load(input);
        }
        return properties;
    }

    private static void saveProperties(Properties properties) throws IOException {
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
