package com.mcworldexplorer.map;

import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        WorldMapCacheCleaner.ClearResult result = new WorldMapCacheCleaner().clear(selected);

        assertFalse(Files.exists(WorldCachePaths.worldDirectory(selected)));
        assertTrue(Files.isRegularFile(otherFile));
        assertEquals(1, result.deletedFiles());
        assertEquals(8, result.deletedBytes());
        assertFalse(result.hasFailures());
    }

    @Test
    void inspectsFilesAndBytesBeforeClearing() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.resolve("app").toString());
        WorldInfo world = new WorldInfo(tempDir.resolve("world"));
        Path worldRoot = WorldCachePaths.worldDirectory(world);
        Files.createDirectories(worldRoot.resolve("nested"));
        Files.write(worldRoot.resolve("preview.png"), new byte[] {1, 2, 3});
        Files.write(worldRoot.resolve("nested/tile.png"), new byte[] {4, 5});

        WorldMapCacheCleaner.Summary summary = new WorldMapCacheCleaner().inspect(world);

        assertEquals(2, summary.fileCount());
        assertEquals(5, summary.bytes());
    }

    @Test
    void missingCacheHasEmptySummaryAndResult() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.resolve("app").toString());
        WorldInfo world = new WorldInfo(tempDir.resolve("world"));
        WorldMapCacheCleaner cleaner = new WorldMapCacheCleaner();

        assertEquals(new WorldMapCacheCleaner.Summary(0, 0), cleaner.inspect(world));
        assertEquals(
                new WorldMapCacheCleaner.ClearResult(0, 0, 0, null),
                cleaner.clear(world));
    }

    @Test
    void reportsPartialDeletionAndContinuesWithOtherFiles() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.resolve("app").toString());
        WorldInfo world = new WorldInfo(tempDir.resolve("world"));
        Path worldRoot = WorldCachePaths.worldDirectory(world);
        Path blocked = worldRoot.resolve("blocked.bin");
        Path removable = worldRoot.resolve("removable.bin");
        Files.createDirectories(worldRoot);
        Files.write(blocked, new byte[] {1, 2, 3, 4});
        Files.write(removable, new byte[] {5, 6});
        WorldMapCacheCleaner cleaner = new WorldMapCacheCleaner(path -> {
            if (path.equals(blocked)) {
                throw new IOException("blocked for test");
            }
            Files.delete(path);
        });

        WorldMapCacheCleaner.ClearResult result = cleaner.clear(world);

        assertTrue(Files.exists(blocked));
        assertFalse(Files.exists(removable));
        assertEquals(1, result.deletedFiles());
        assertEquals(2, result.deletedBytes());
        assertTrue(result.hasFailures());
        assertTrue(result.failedEntries() >= 1);
        assertEquals("blocked for test", result.firstFailure());
    }
}
