package com.mcworldexplorer.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableSettingsTest {
    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(PortablePaths.APPLICATION_HOME_PROPERTY);

    @AfterEach
    void restoreApplicationHome() {
        if (originalHome == null) {
            System.clearProperty(PortablePaths.APPLICATION_HOME_PROPERTY);
        } else {
            System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void missingSettingsDoNotCreateConfigDirectory() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());

        assertTrue(new PortableSettings().loadCustomSavesPath().isEmpty());
        assertTrue(Files.notExists(applicationRoot.resolve("config")));
    }

    @Test
    void savesAndLoadsCustomPathInsidePortableConfig() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Path selectedPath = tempDir.resolve("整合包/实例");
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());
        PortableSettings settings = new PortableSettings();

        settings.saveCustomSavesPath(selectedPath);

        assertEquals(
                selectedPath.toAbsolutePath().normalize(),
                settings.loadCustomSavesPath().orElseThrow());
        assertTrue(Files.isRegularFile(applicationRoot.resolve("config/settings.properties")));
    }

    @Test
    void replacesExistingSettingWithoutLeavingTemporaryFiles() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());
        PortableSettings settings = new PortableSettings();

        settings.saveCustomSavesPath(tempDir.resolve("first"));
        settings.saveCustomSavesPath(tempDir.resolve("second"));

        assertEquals(
                tempDir.resolve("second").toAbsolutePath().normalize(),
                settings.loadCustomSavesPath().orElseThrow());
        try (var files = Files.list(applicationRoot.resolve("config"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void reportsConfigDirectoryFailureWithoutFallback() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Files.writeString(applicationRoot.resolve("config"), "occupied");
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());

        assertThrows(
                IOException.class,
                () -> new PortableSettings().saveCustomSavesPath(tempDir.resolve("worlds")));
    }
}
