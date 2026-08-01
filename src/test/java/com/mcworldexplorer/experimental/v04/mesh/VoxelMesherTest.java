package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;
import com.mcworldexplorer.experimental.v04.data.VoxelChunk;
import com.mcworldexplorer.experimental.v04.data.VoxelChunkNeighborhood;
import com.mcworldexplorer.experimental.v04.data.VoxelLoadWarning;
import com.mcworldexplorer.experimental.v04.data.VoxelSection;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class VoxelMesherTest {
    private static final VoxelBlockState STONE = state("minecraft:stone");
    private static final VoxelBlockState WATER = state("minecraft:water");

    private final VoxelMesher mesher = new VoxelMesher();

    @Test
    void emitsSixFacesForOneSolidBlock() throws Exception {
        RenderSnapshot snapshot = mesher.build(neighborhood(chunk(0, 0, 0, block(1, 2, 3, STONE))));

        assertEquals(1, snapshot.blockCount());
        assertEquals(1, snapshot.solidBlockCount());
        assertEquals(0, snapshot.fluidBlockCount());
        assertEquals(0, snapshot.fluidFaceCount());
        assertEquals(6, snapshot.faceCount());
        assertEquals(24, snapshot.vertexCount());
        assertEquals(36, snapshot.indexCount());
        assertEquals(new MeshBounds(1, 2, 3, 2, 3, 4), snapshot.bounds());
        assertValid(snapshot);
    }

    @Test
    void cullsInternalSolidAndFluidFaces() throws Exception {
        RenderSnapshot solids = mesher.build(neighborhood(chunk(
                0, 0, 0,
                block(1, 2, 3, STONE),
                block(2, 2, 3, STONE))));
        RenderSnapshot fluids = mesher.build(neighborhood(chunk(
                0, 0, 0,
                block(1, 2, 3, WATER),
                block(1, 3, 3, WATER))));

        assertEquals(10, solids.faceCount());
        assertEquals(10, fluids.faceCount());
        assertEquals(0, fluids.solidBlockCount());
        assertEquals(2, fluids.fluidBlockCount());
        assertEquals(10, fluids.fluidFaceCount());
    }

    @Test
    void hidesFluidSolidInterfaceAndTreatsWaterloggedAsSolid() throws Exception {
        VoxelBlockState waterlogged = new VoxelBlockState(
                "minecraft:oak_slab",
                Map.of("waterlogged", "true"));
        RenderSnapshot snapshot = mesher.build(neighborhood(chunk(
                0, 0, 0,
                block(1, 2, 3, WATER),
                block(2, 2, 3, waterlogged))));

        assertEquals(10, snapshot.faceCount());
        assertEquals(2, snapshot.blockCount());
    }

    @Test
    void usesEastNeighborForBoundaryCulling() throws Exception {
        VoxelChunk target = chunk(0, 0, 0, block(15, 2, 3, STONE));
        VoxelChunk east = chunk(1, 0, 0, block(0, 2, 3, STONE));
        EnumMap<VoxelChunkNeighborhood.Direction, Optional<VoxelChunk>> neighbors =
                emptyNeighbors();
        neighbors.put(VoxelChunkNeighborhood.Direction.EAST, Optional.of(east));

        RenderSnapshot snapshot = mesher.build(new VoxelChunkNeighborhood(
                target, neighbors, List.of()));

        assertEquals(5, snapshot.faceCount());
        assertTrue(snapshot.boundaryComplete());
    }

    @Test
    void preservesNegativeYAndBoundaryWarning() throws Exception {
        VoxelChunk target = chunk(0, 0, -1, block(1, 15, 1, STONE));
        VoxelLoadWarning warning = new VoxelLoadWarning(
                VoxelChunkNeighborhood.Direction.WEST,
                -1,
                0,
                "damaged neighbor");

        RenderSnapshot snapshot = mesher.build(new VoxelChunkNeighborhood(
                target, emptyNeighbors(), List.of(warning)));

        assertEquals(-1, snapshot.bounds().minY());
        assertEquals(0, snapshot.bounds().maxY());
        assertFalse(snapshot.boundaryComplete());
        assertEquals(1, snapshot.warnings().size());
    }

    @Test
    void iteratesOnlyPresentSectionsAcrossLargeSparseHeightRange() throws Exception {
        VoxelChunk target = new VoxelChunk(
                0,
                0,
                List.of(
                        section(-100_000, block(1, 0, 1, STONE)),
                        section(100_000, block(1, 15, 1, STONE))));

        RenderSnapshot snapshot = assertTimeout(
                Duration.ofSeconds(2),
                () -> mesher.build(neighborhood(target)));

        assertEquals(2, snapshot.blockCount());
        assertEquals(12, snapshot.faceCount());
        assertEquals(-1_600_000, snapshot.bounds().minY());
        assertEquals(1_600_016, snapshot.bounds().maxY());
    }

    private static void assertValid(RenderSnapshot snapshot) {
        for (MeshBatch batch : snapshot.batches()) {
            assertEquals(batch.positions().length, batch.normals().length);
            for (int index : batch.indices()) {
                assertTrue(index >= 0 && index < batch.vertexCount());
            }
            for (float value : batch.positions()) {
                assertTrue(Float.isFinite(value));
            }
            for (float value : batch.normals()) {
                assertTrue(Float.isFinite(value));
            }
        }
    }

    private static VoxelChunkNeighborhood neighborhood(VoxelChunk target) {
        return new VoxelChunkNeighborhood(target, emptyNeighbors(), List.of());
    }

    private static EnumMap<VoxelChunkNeighborhood.Direction, Optional<VoxelChunk>> emptyNeighbors() {
        EnumMap<VoxelChunkNeighborhood.Direction, Optional<VoxelChunk>> neighbors =
                new EnumMap<>(VoxelChunkNeighborhood.Direction.class);
        for (VoxelChunkNeighborhood.Direction direction : VoxelChunkNeighborhood.Direction.values()) {
            neighbors.put(direction, Optional.empty());
        }
        return neighbors;
    }

    private static VoxelChunk chunk(
            int chunkX,
            int chunkZ,
            int sectionY,
            Block... blocks) throws Exception {
        return new VoxelChunk(chunkX, chunkZ, List.of(section(sectionY, blocks)));
    }

    private static VoxelSection section(int sectionY, Block... blocks) throws Exception {
        List<VoxelBlockState> palette = new java.util.ArrayList<>();
        palette.add(VoxelBlockState.AIR);
        long[] data = new long[256];
        for (Block block : blocks) {
            int paletteIndex = palette.indexOf(block.state());
            if (paletteIndex < 0) {
                palette.add(block.state());
                paletteIndex = palette.size() - 1;
            }
            setPadded(data, 4, block.localY() * 256 + block.localZ() * 16 + block.localX(), paletteIndex);
        }
        return new VoxelSection(sectionY, palette, data);
    }

    private static Block block(int x, int y, int z, VoxelBlockState state) {
        return new Block(x, y, z, state);
    }

    private static VoxelBlockState state(String name) {
        return new VoxelBlockState(name, Map.of());
    }

    private static void setPadded(long[] data, int bits, int index, int value) {
        int valuesPerLong = Long.SIZE / bits;
        int longIndex = index / valuesPerLong;
        int bitOffset = index % valuesPerLong * bits;
        data[longIndex] |= (long) value << bitOffset;
    }

    private record Block(int localX, int localY, int localZ, VoxelBlockState state) {
    }
}
