package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLoadResultTest {
    @Test
    void keepsSuccessAndFailurePayloadsDistinct() throws Exception {
        ChunkCoordinate coordinate = new ChunkCoordinate(1, -2);
        VoxelChunk chunk = new VoxelChunk(1, -2, List.of());

        ChunkLoadResult success = ChunkLoadResult.success(coordinate, chunk, 3, 4);
        ChunkLoadResult missing = ChunkLoadResult.withoutChunk(
                coordinate, ChunkLoadStatus.MISSING, "missing", 5, 0);

        assertTrue(success.optionalChunk().isPresent());
        assertTrue(missing.optionalChunk().isEmpty());
        assertEquals("missing", missing.detail());
        assertThrows(IllegalArgumentException.class,
                () -> ChunkLoadResult.withoutChunk(
                        coordinate, ChunkLoadStatus.SUCCESS, "", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkLoadResult(
                        coordinate, ChunkLoadStatus.CORRUPT, chunk, "bad", 0, 0));
    }
}
