package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewGeneratorRequestTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesFromRequestedDimensionWithoutFallingBackToOverworld() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("world"));
        Path regionDirectory = Files.createDirectories(worldFolder.resolve("DIM-1/region"));
        Files.createFile(regionDirectory.resolve("r.0.0.mca"));
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewRequest request = new PreviewRequest(
                WorldDimension.nether(worldFolder),
                new PreviewCenter(0, 0, PreviewCenterSource.DIMENSION_ORIGIN),
                PreviewLayer.heightBand(64, 95));

        PreviewGenerationResult result = new PreviewGenerator().generate(world, request);

        assertEquals(request.center(), result.center());
        assertEquals(0, result.sampledChunks());
        assertEquals(result.totalChunks(), result.missingChunks());
        assertEquals(0, result.failedChunks());
    }

    @Test
    void rejectsDimensionDirectoriesOutsideSelectedWorld() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("world-safe"));
        Path outside = Files.createDirectories(tempDir.resolve("outside/region"));
        WorldInfo world = new WorldInfo(worldFolder);
        WorldDimension dimension = new WorldDimension(
                "example:outside",
                "outside",
                outside,
                DimensionKind.MOD);
        PreviewRequest request = new PreviewRequest(
                dimension,
                new PreviewCenter(0, 0, PreviewCenterSource.DIMENSION_ORIGIN),
                PreviewLayer.surfaceOverview());

        assertThrows(IllegalArgumentException.class,
                () -> new PreviewGenerator().generate(world, request));
    }

    @Test
    void stillReportsNonEmptyTruncatedRegionFilesAsFailures() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("world-damaged"));
        Path regionDirectory = Files.createDirectories(worldFolder.resolve("DIM-1/region"));
        Files.write(regionDirectory.resolve("r.0.0.mca"), new byte[] {1});
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewRequest request = new PreviewRequest(
                WorldDimension.nether(worldFolder),
                new PreviewCenter(0, 0, PreviewCenterSource.DIMENSION_ORIGIN),
                PreviewLayer.heightBand(64, 95));

        PreviewGenerationResult result = new PreviewGenerator().generate(world, request);

        assertTrue(result.failedChunks() > 0);
        assertEquals(result.totalChunks(), result.missingChunks() + result.failedChunks());
    }

    @Test
    void keepsPreviewBoundsOrderedAtExtremeIntegerCoordinates() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("world-extreme-center"));
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewCenter center = new PreviewCenter(
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                PreviewCenterSource.WORLD_SPAWN);
        PreviewRequest request = new PreviewRequest(
                WorldDimension.overworld(worldFolder),
                center,
                PreviewLayer.surfaceOverview());

        PreviewGenerationResult result = new PreviewGenerator().generate(world, request);

        assertEquals(center, result.center());
        assertTrue(result.totalChunks() > 0);
        assertEquals(result.totalChunks(), result.missingChunks());
        assertEquals(0, result.failedChunks());
    }
}
