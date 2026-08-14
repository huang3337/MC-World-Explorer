package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelAreaLoaderProgressTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsEveryCompletedTargetAndHaloChunk() throws IOException {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        Files.createDirectory(world.resolve("region"));
        VoxelAreaRequest request = new VoxelAreaRequest(
                world, WorldDimension.OVERWORLD_ID,
                ChunkRectangle.target(0, 0, 0, 0));
        List<Integer> completedCounts = new ArrayList<>();

        VoxelAreaLoadResult result = new VoxelAreaLoader().load(
                request,
                CancellationSignal.NONE,
                (coordinate, status, completed, total) -> {
                    assertEquals(ChunkLoadStatus.MISSING, status);
                    assertEquals(9, total);
                    completedCounts.add(completed);
                });

        assertEquals(9, result.results().size());
        assertEquals(9, completedCounts.size());
        assertEquals(9, completedCounts.getLast());
        assertTrue(completedCounts.stream().allMatch(value -> value >= 1 && value <= 9));
    }
}
