package com.mcworldexplorer.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class WorldScannerTest {

    @TempDir
    Path tempDir;

    @Test
    public void testScanNonExistentDirectory() {
        Path fakePath = Paths.get("some/fake/path/that/does/not/exist");
        List<WorldInfo> worlds = WorldScanner.scanWorlds(fakePath);
        assertNotNull(worlds);
        assertTrue(worlds.isEmpty());
    }

    @Test
    public void testGetDefaultGameRootDoesNotCrash() {
        Path defaultRoot = WorldScanner.getDefaultGameRoot();
        // Since this runs on various environments, defaultRoot could be null or valid.
        // We just ensure it doesn't throw exceptions.
        if (defaultRoot != null) {
            assertTrue(defaultRoot.isAbsolute() || !defaultRoot.toString().isEmpty());
        }
    }

    @Test
    void scansDirectSavesDirectory() throws IOException {
        Path saves = Files.createDirectory(tempDir.resolve("saves"));
        createWorld(saves, "direct-world");

        assertEquals(1, worldCount(WorldScanner.scanSelectedPath(saves)));
    }

    @Test
    void scansGameRootWithDefaultAndVersionWorlds() throws IOException {
        Path gameRoot = Files.createDirectory(tempDir.resolve(".minecraft"));
        createWorld(Files.createDirectory(gameRoot.resolve("saves")), "default-world");
        Path instance = Files.createDirectories(gameRoot.resolve("versions/modpack"));
        createWorld(Files.createDirectory(instance.resolve("saves")), "modpack-world");

        Map<String, List<WorldInfo>> result = WorldScanner.scanSelectedPath(gameRoot);

        assertEquals(2, result.size());
        assertEquals(2, worldCount(result));
    }

    @Test
    void scansVersionsDirectoryAndSingleInstanceRoot() throws IOException {
        Path versions = Files.createDirectory(tempDir.resolve("versions"));
        Path instance = Files.createDirectory(versions.resolve("modpack"));
        createWorld(Files.createDirectory(instance.resolve("saves")), "modpack-world");

        assertEquals(1, worldCount(WorldScanner.scanSelectedPath(versions)));
        assertEquals(1, worldCount(WorldScanner.scanSelectedPath(instance)));
    }

    @Test
    void scansSingleWorldAndRejectsUnrecognizedDirectory() throws IOException {
        Path world = createWorld(tempDir, "single-world");
        Path empty = Files.createDirectory(tempDir.resolve("empty"));

        assertEquals(1, worldCount(WorldScanner.scanSelectedPath(world)));
        assertTrue(WorldScanner.scanSelectedPath(empty).isEmpty());
    }

    private static Path createWorld(Path savesDirectory, String folderName) throws IOException {
        Path world = Files.createDirectory(savesDirectory.resolve(folderName));
        CompoundBinaryTag data = CompoundBinaryTag.builder().putString("LevelName", folderName).build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", data).build();
        BinaryTagIO.writer().write(root, world.resolve("level.dat"), BinaryTagIO.Compression.GZIP);
        return world;
    }

    private static int worldCount(Map<String, List<WorldInfo>> groupedWorlds) {
        return groupedWorlds.values().stream().mapToInt(List::size).sum();
    }
}
