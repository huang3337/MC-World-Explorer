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

    public Optional<Path> loadCustomSavesPath() throws IOException {
        Path settingsFile = settingsFile();
        if (!Files.isRegularFile(settingsFile)) {
            return Optional.empty();
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
        }
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

    public void saveCustomSavesPath(Path selectedPath) throws IOException {
        if (selectedPath == null) {
            throw new IllegalArgumentException("selectedPath must not be null");
        }

        Path configDirectory = PortablePaths.configDirectory();
        Files.createDirectories(configDirectory);
        Path settingsFile = settingsFile();
        Path temporary = Files.createTempFile(configDirectory, ".settings-", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty(
                    CUSTOM_SAVES_PATH,
                    selectedPath.toAbsolutePath().normalize().toString());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "MC World Explorer portable settings");
            }
            replace(temporary, settingsFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path settingsFile() {
        return PortablePaths.configDirectory().resolve(SETTINGS_FILE_NAME);
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
