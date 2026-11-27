package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldDimensionDiscoveryIntegrationTest {
    @Test
    void discoversRealVanillaAndModDimensionsInsideTheirWorlds() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        Map<String, List<WorldInfo>> grouped = WorldScanner.scanSelectedPath(Path.of(versionsDirectory));
        List<WorldInfo> worlds = grouped.values().stream().flatMap(List::stream).toList();
        assertTrue(!worlds.isEmpty(), "No real worlds found for dimension verification");

        Map<DimensionKind, Integer> counts = new EnumMap<>(DimensionKind.class);
        for (WorldInfo world : worlds) {
            Path worldRoot = world.getFolderPath().toAbsolutePath().normalize();
            for (WorldDimension dimension : WorldDimensionDiscovery.discover(world)) {
                Path regionDirectory = dimension.regionDirectory().toAbsolutePath().normalize();
                assertTrue(regionDirectory.startsWith(worldRoot),
                        "Dimension escaped its world: " + regionDirectory);
                if (dimension.kind() != DimensionKind.OVERWORLD) {
                    assertTrue(Files.isDirectory(regionDirectory),
                            "Discovered dimension has no Region directory: " + regionDirectory);
                }
                counts.merge(dimension.kind(), 1, Integer::sum);
            }
        }

        assertTrue(counts.getOrDefault(DimensionKind.NETHER, 0) > 0, "No Nether dimensions found");
        assertTrue(counts.getOrDefault(DimensionKind.END, 0) > 0, "No End dimensions found");
        assertTrue(counts.getOrDefault(DimensionKind.MOD, 0) > 0, "No Mod dimensions found");
        System.out.println("Discovered real dimensions without writes: " + counts);
    }
}
