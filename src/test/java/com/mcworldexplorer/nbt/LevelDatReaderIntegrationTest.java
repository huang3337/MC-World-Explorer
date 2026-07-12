package com.mcworldexplorer.nbt;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LevelDatReaderIntegrationTest {
    @Test
    void parsesRealWorldsWithoutModifyingLevelDatFiles() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        List<Path> levelDatFiles;
        try (Stream<Path> paths = Files.walk(Path.of(versionsDirectory))) {
            levelDatFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("level.dat"))
                    .toList();
        }
        assertFalse(levelDatFiles.isEmpty(), "No level.dat files found for integration verification");

        Map<Path, FileSnapshot> before = snapshot(levelDatFiles);
        List<Path> failures = new ArrayList<>();
        for (Path levelDat : levelDatFiles) {
            WorldInfo info = LevelDatReader.readLevelDat(levelDat.getParent());
            if (!info.isParsed()) {
                failures.add(levelDat);
            }
        }
        Map<Path, FileSnapshot> after = snapshot(levelDatFiles);

        assertTrue(failures.isEmpty(), "Failed to parse: " + failures);
        assertEquals(before, after, "Real level.dat files changed during read-only verification");
        System.out.printf("Verified %d real level.dat files without modification.%n", levelDatFiles.size());
    }

    private static Map<Path, FileSnapshot> snapshot(List<Path> files) throws IOException {
        Map<Path, FileSnapshot> snapshots = new LinkedHashMap<>();
        for (Path file : files) {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            snapshots.put(file, new FileSnapshot(attributes.size(), attributes.lastModifiedTime().toMillis()));
        }
        return snapshots;
    }

    private record FileSnapshot(long size, long lastModifiedMillis) {
    }
}
