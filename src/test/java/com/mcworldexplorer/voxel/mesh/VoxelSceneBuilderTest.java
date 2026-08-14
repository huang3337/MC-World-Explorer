package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelSceneBuilderTest {
    @Test
    void keepsPartialSuccessAndSceneRelativeBounds() throws Exception {
        ChunkRectangle target = ChunkRectangle.target(10, -2, 11, -2);
        Map<ChunkCoordinate, ChunkLoadResult> results = missingHalo(target);
        ChunkCoordinate first = new ChunkCoordinate(10, -2);
        ChunkCoordinate second = new ChunkCoordinate(11, -2);
        results.put(first, ChunkLoadResult.success(first, singleStone(10, -2), 0, 0));
        results.put(second, ChunkLoadResult.withoutChunk(
                second, ChunkLoadStatus.CORRUPT, "damaged", 0, 0));
        Path world = Path.of(".").toAbsolutePath().normalize();
        VoxelAreaLoadResult area = new VoxelAreaLoadResult(
                world, WorldDimension.overworld(world), target, results);

        VoxelSceneResult result = new VoxelSceneBuilder().build(area, CancellationSignal.NONE);

        assertTrue(result.snapshot().isPresent());
        assertEquals(160L, result.snapshot().orElseThrow().anchorWorldX());
        assertEquals(-32L, result.snapshot().orElseThrow().anchorWorldZ());
        assertEquals(ChunkLoadStatus.SUCCESS, result.targetStatuses().get(first));
        assertEquals(ChunkLoadStatus.CORRUPT, result.targetStatuses().get(second));
    }

    @Test
    void returnsNoSnapshotWhenEveryTargetFails() {
        ChunkRectangle target = ChunkRectangle.target(0, 0, 0, 0);
        Map<ChunkCoordinate, ChunkLoadResult> results = missingHalo(target);
        Path world = Path.of(".").toAbsolutePath().normalize();
        VoxelAreaLoadResult area = new VoxelAreaLoadResult(
                world, WorldDimension.overworld(world), target, results);

        VoxelSceneResult result = new VoxelSceneBuilder().build(area, CancellationSignal.NONE);

        assertTrue(result.snapshot().isEmpty());
        assertEquals(ChunkLoadStatus.MISSING,
                result.targetStatuses().get(new ChunkCoordinate(0, 0)));
    }

    @Test
    void aggregatesFallbackWarningsAcrossChunks() throws Exception {
        ChunkRectangle target = ChunkRectangle.target(0, 0, 1, 0);
        Map<ChunkCoordinate, ChunkLoadResult> results = missingHalo(target);
        ChunkCoordinate first = new ChunkCoordinate(0, 0);
        ChunkCoordinate second = new ChunkCoordinate(1, 0);
        results.put(first, ChunkLoadResult.success(first, singleBlock(0, 0, "example:machine"), 0, 0));
        results.put(second, ChunkLoadResult.success(second, singleBlock(1, 0, "example:machine"), 0, 0));
        Path world = Path.of(".").toAbsolutePath().normalize();
        VoxelAreaLoadResult area = new VoxelAreaLoadResult(
                world, WorldDimension.overworld(world), target, results);

        MeshWarning warning = new VoxelSceneBuilder()
                .build(area, CancellationSignal.NONE)
                .snapshot().orElseThrow().warnings().stream()
                .filter(item -> item.code() == MeshWarning.Code.FALLBACK_BLOCK)
                .findFirst().orElseThrow();

        assertEquals("example:machine", warning.detail());
        assertEquals(2, warning.occurrences());
    }

    private static Map<ChunkCoordinate, ChunkLoadResult> missingHalo(ChunkRectangle target) {
        Map<ChunkCoordinate, ChunkLoadResult> results = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : target.expanded(1).coordinates()) {
            results.put(coordinate, ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.MISSING, "missing", 0, 0));
        }
        return results;
    }

    private static VoxelChunk singleStone(int chunkX, int chunkZ) throws Exception {
        return singleBlock(chunkX, chunkZ, "minecraft:stone");
    }

    private static VoxelChunk singleBlock(int chunkX, int chunkZ, String name) throws Exception {
        List<BlockState> palette = List.of(
                BlockState.AIR,
                new BlockState(name, Map.of()));
        long[] data = new long[256];
        data[0] = 1;
        return new VoxelChunk(
                chunkX,
                chunkZ,
                List.of(new ChunkSection(0, palette, data)));
    }
}
