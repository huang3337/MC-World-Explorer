package com.mcworldexplorer.map;

import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMapCacheCleanerTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(HOME_PROPERTY);

    @AfterEach
    void restoreHome() {
        if (originalHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void clearsOnlySelectedWorldCache() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.resolve("app").toString());
        WorldInfo selected = new WorldInfo(tempDir.resolve("world-a"));
        WorldInfo other = new WorldInfo(tempDir.resolve("world-b"));
        Path selectedFile = WorldCachePaths.worldDirectory(selected)
                .resolve("map-v03/tile.png");
        Path otherFile = WorldCachePaths.worldDirectory(other)
                .resolve("preview.png");
        Files.createDirectories(selectedFile.getParent());
        Files.createDirectories(otherFile.getParent());
        Files.writeString(selectedFile, "selected");
        Files.writeString(otherFile, "other");

        new WorldMapCacheCleaner().clear(selected);

        assertFalse(Files.exists(WorldCachePaths.worldDirectory(selected)));
        assertTrue(Files.isRegularFile(otherFile));
    }
}
