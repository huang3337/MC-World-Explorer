package com.mcworldexplorer.storage;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldCachePathsTest {
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
    void preservesExistingWorldAndDimensionIdentityRules() {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        WorldInfo world = new WorldInfo(tempDir.resolve("world-folder"));
        world.setLevelName("Test: World");

        String expectedWorld = "Test_ World-"
                + WorldCachePaths.textHash(world.getFolderPath().toAbsolutePath().normalize().toString());
        String expectedDimension = "minecraft_overworld-"
                + WorldCachePaths.textHash(WorldDimension.OVERWORLD_ID);

        assertEquals(expectedWorld, WorldCachePaths.worldDirectoryName(world));
        assertEquals(expectedDimension, WorldCachePaths.dimensionDirectoryName(
                WorldDimension.overworld(world.getFolderPath())));
        assertTrue(WorldCachePaths.worldDirectory(world).startsWith(PortablePaths.cacheDirectory()));
    }

    @Test
    void fallsBackForUnsafeNamesAndSeparatesWorldPaths() {
        WorldInfo first = new WorldInfo(tempDir.resolve("first"));
        first.setLevelName(".. ");
        WorldInfo second = new WorldInfo(tempDir.resolve("second"));
        second.setLevelName(".. ");

        assertTrue(WorldCachePaths.worldDirectoryName(first).startsWith("world-"));
        assertNotEquals(
                WorldCachePaths.worldDirectoryName(first),
                WorldCachePaths.worldDirectoryName(second));
    }
}
