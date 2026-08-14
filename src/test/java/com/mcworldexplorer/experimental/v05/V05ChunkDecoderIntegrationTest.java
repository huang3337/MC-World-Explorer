package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;
import com.mcworldexplorer.experimental.v04.data.VoxelChunkParser;
import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.ChunkNbtDecoder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V05ChunkDecoderIntegrationTest {
    @Test
    void matchesIndependentlyVerifiedV04DecoderForConfiguredRealChunks() throws Exception {
        String configuredWorld = System.getenv("MCWORLD_V05_TEST_WORLD");
        assumeTrue(configuredWorld != null && !configuredWorld.isBlank());

        Path regionDirectory = Path.of(configuredWorld).toRealPath().resolve("region");
        assumeTrue(Files.isDirectory(regionDirectory));
        long blocksCompared = 0;
        for (Coordinate coordinate : configuredCoordinates()) {
            Path regionFile = regionDirectory.resolve("r."
                    + Math.floorDiv(coordinate.x(), 32) + "."
                    + Math.floorDiv(coordinate.z(), 32) + ".mca");
            try (RegionFileReader reader = new RegionFileReader(regionFile)) {
                RegionChunkData data = reader.readChunk(
                                Math.floorMod(coordinate.x(), 32),
                                Math.floorMod(coordinate.z(), 32))
                        .orElseThrow(() -> new AssertionError(
                                "Configured chunk is missing: " + coordinate));
                com.mcworldexplorer.experimental.v04.data.VoxelChunk reference =
                        VoxelChunkParser.read(data, coordinate.x(), coordinate.z());
                com.mcworldexplorer.voxel.data.VoxelChunk actual =
                        ChunkNbtDecoder.read(data, coordinate.x(), coordinate.z()).chunk();

                List<Integer> actualSectionYs = actual.sections().stream()
                        .map(section -> section.sectionY())
                        .toList();
                assertEquals(reference.sectionYs(), actualSectionYs);
                for (int sectionY : actualSectionYs) {
                    int baseY = Math.multiplyExact(sectionY, 16);
                    for (int localY = 0; localY < 16; localY++) {
                        int y = Math.addExact(baseY, localY);
                        for (int localZ = 0; localZ < 16; localZ++) {
                            for (int localX = 0; localX < 16; localX++) {
                                VoxelBlockState expected = reference.blockState(localX, y, localZ);
                                BlockState found = actual.blockState(localX, y, localZ);
                                assertEquals(expected.name(), found.name());
                                assertEquals(expected.properties(), found.properties());
                                blocksCompared++;
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("V0.5 decoder comparison: chunks=%d, blocks=%d%n",
                configuredCoordinates().size(), blocksCompared);
    }

    private static List<Coordinate> configuredCoordinates() {
        String value = System.getenv("MCWORLD_V05_TEST_CHUNKS");
        if (value == null || value.isBlank()) {
            value = "0,30;1,29;1,30";
        }
        List<Coordinate> coordinates = new ArrayList<>();
        for (String pair : value.split(";")) {
            String[] parts = pair.trim().split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid MCWORLD_V05_TEST_CHUNKS entry: " + pair);
            }
            coordinates.add(new Coordinate(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())));
        }
        return List.copyOf(coordinates);
    }

    private record Coordinate(int x, int z) {
    }
}
