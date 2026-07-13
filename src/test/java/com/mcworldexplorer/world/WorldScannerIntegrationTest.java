package com.mcworldexplorer.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldScannerIntegrationTest {
    @Test
    void scansEveryWorldInRealVersionsDirectory() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        Path versions = Path.of(versionsDirectory);
        long levelDatCount;
        try (Stream<Path> paths = Files.walk(versions)) {
            levelDatCount = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("level.dat"))
                    .count();
        }
        assertTrue(levelDatCount > 0, "No level.dat files found for scanner verification");

        Map<String, List<WorldInfo>> groupedWorlds = WorldScanner.scanSelectedPath(versions);
        int scannedWorldCount = groupedWorlds.values().stream().mapToInt(List::size).sum();

        assertEquals(levelDatCount, scannedWorldCount, "Not every real world was discovered");
    }
}
