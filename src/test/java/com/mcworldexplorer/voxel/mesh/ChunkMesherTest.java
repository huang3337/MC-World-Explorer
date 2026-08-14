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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMesherTest {
    private static final ChunkCoordinate TARGET = new ChunkCoordinate(0, 0);
    private static final ChunkRectangle RECTANGLE = ChunkRectangle.target(0, 0, 0, 0);

    @Test
    void buildsSingleCubeAndCullsInternalOpaqueFace() throws Exception {
        VoxelChunk single = chunk(0, 0, Map.of(
                index(2, 3, 4), state("minecraft:stone")));
        ChunkMeshSnapshot singleMesh = mesh(area(Map.of(TARGET, success(TARGET, single))));
        assertEquals(1, singleMesh.blockCount());
        assertEquals(6, singleMesh.faceCount());
        assertEquals(24, singleMesh.vertexCount());
        assertEquals(36, singleMesh.indexCount());

        VoxelChunk pair = chunk(0, 0, Map.of(
                index(2, 3, 4), state("minecraft:stone"),
                index(3, 3, 4), state("minecraft:dirt")));
        ChunkMeshSnapshot pairMesh = mesh(area(Map.of(TARGET, success(TARGET, pair))));
        assertEquals(2, pairMesh.blockCount());
        assertEquals(10, pairMesh.faceCount());
    }

    @Test
    void cullsAcrossChunkBoundaryAndPreservesFailedBoundary() throws Exception {
        VoxelChunk target = chunk(0, 0, Map.of(
                index(15, 5, 8), state("minecraft:stone")));
        ChunkCoordinate east = new ChunkCoordinate(1, 0);
        VoxelChunk neighbor = chunk(1, 0, Map.of(
                index(0, 5, 8), state("minecraft:stone")));

        ChunkMeshSnapshot complete = mesh(area(Map.of(
                TARGET, success(TARGET, target),
                east, success(east, neighbor))));
        assertEquals(5, complete.faceCount());

        ChunkMeshSnapshot failed = mesh(area(Map.of(
                TARGET, success(TARGET, target),
                east, ChunkLoadResult.withoutChunk(
                        east, ChunkLoadStatus.CORRUPT, "damaged", 0, 0))));
        assertEquals(6, failed.faceCount());
        assertTrue(failed.warnings().stream()
                .anyMatch(warning -> warning.code() == MeshWarning.Code.NEIGHBOR_UNAVAILABLE));
    }

    @Test
    void keepsOnlyAirExposedFluidShellAndAggregatesFallback() throws Exception {
        VoxelChunk chunk = chunk(0, 0, Map.of(
                index(4, 0, 4), state("minecraft:water"),
                index(4, 1, 4), state("minecraft:water"),
                index(7, 0, 7), state("example:magic_water"),
                index(8, 0, 7), state("example:magic_water")));

        ChunkMeshSnapshot snapshot = mesh(area(Map.of(TARGET, success(TARGET, chunk))));

        int fluidFaces = snapshot.batches().stream()
                .filter(batch -> batch.layer() == RenderLayer.FLUID)
                .mapToInt(MeshBatch::faceCount)
                .sum();
        assertEquals(10, fluidFaces);
        assertTrue(snapshot.batches().stream()
                .anyMatch(batch -> batch.layer() == RenderLayer.FALLBACK));
        assertEquals(1, snapshot.warnings().stream()
                .filter(warning -> warning.code() == MeshWarning.Code.FALLBACK_BLOCK)
                .count());
        assertEquals(2, snapshot.warnings().stream()
                .filter(warning -> warning.code() == MeshWarning.Code.FALLBACK_BLOCK)
                .findFirst().orElseThrow().occurrences());
    }

    @Test
    void cullsOnlyMatchingTransparentStatesAndRespondsToCancellation() throws Exception {
        VoxelChunk matching = chunk(0, 0, Map.of(
                index(1, 1, 1), state("minecraft:glass"),
                index(2, 1, 1), state("minecraft:glass")));
        assertEquals(10, mesh(area(Map.of(TARGET, success(TARGET, matching)))).faceCount());

        VoxelChunk differing = chunk(0, 0, Map.of(
                index(1, 1, 1), state("minecraft:glass"),
                index(2, 1, 1), state("minecraft:tinted_glass")));
        assertEquals(12, mesh(area(Map.of(TARGET, success(TARGET, differing)))).faceCount());

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> new ChunkMesher().build(
                        area(Map.of(TARGET, success(TARGET, matching))),
                        TARGET,
                        () -> true));
    }

    private static ChunkMeshSnapshot mesh(VoxelAreaLoadResult area) throws Exception {
        return new ChunkMesher().build(area, TARGET, CancellationSignal.NONE);
    }

    private static VoxelAreaLoadResult area(Map<ChunkCoordinate, ChunkLoadResult> overrides) {
        Map<ChunkCoordinate, ChunkLoadResult> results = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : RECTANGLE.expanded(1).coordinates()) {
            results.put(coordinate, ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.MISSING, "missing", 0, 0));
        }
        results.putAll(overrides);
        Path world = Path.of(".").toAbsolutePath().normalize();
        return new VoxelAreaLoadResult(world, WorldDimension.overworld(world), RECTANGLE, results);
    }

    private static ChunkLoadResult success(ChunkCoordinate coordinate, VoxelChunk chunk) {
        return ChunkLoadResult.success(coordinate, chunk, 0, 0);
    }

    private static VoxelChunk chunk(
            int chunkX,
            int chunkZ,
            Map<Integer, BlockState> blocks) throws Exception {
        List<BlockState> palette = new ArrayList<>();
        palette.add(BlockState.AIR);
        for (BlockState state : blocks.values()) {
            if (!palette.contains(state)) {
                palette.add(state);
            }
        }
        long[] data = palette.size() == 1 ? new long[0] : new long[256];
        for (Map.Entry<Integer, BlockState> entry : blocks.entrySet()) {
            int paletteIndex = palette.indexOf(entry.getValue());
            setPadded(data, 4, entry.getKey(), paletteIndex);
        }
        return new VoxelChunk(
                chunkX,
                chunkZ,
                List.of(new ChunkSection(0, palette, data)));
    }

    private static BlockState state(String name) {
        return new BlockState(name, Map.of());
    }

    private static int index(int x, int y, int z) {
        return y * 256 + z * 16 + x;
    }

    private static void setPadded(long[] data, int bits, int index, int value) {
        int valuesPerLong = Long.SIZE / bits;
        int longIndex = index / valuesPerLong;
        int bitOffset = index % valuesPerLong * bits;
        data[longIndex] |= (long) value << bitOffset;
    }
}
