package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelBlockContextTest {
    private static VoxelAreaLoadResult area() throws Exception {
        var rectangle = ChunkRectangle.target(-1, -1, -1, -1);
        Map<ChunkCoordinate, ChunkLoadResult> results = new HashMap<>();
        for (var c : rectangle.expanded(1).coordinates())
            results.put(c, ChunkLoadResult.withoutChunk(c, ChunkLoadStatus.MISSING, "not generated", 0, 0));
        var c = new ChunkCoordinate(-1, -1);
        var state = new BlockState("unregistered:stone", Map.of("axis", "y"));
        var chunk = new VoxelChunk(-1, -1, List.of(new ChunkSection(0, List.of(state), new long[0])));
        results.put(c, ChunkLoadResult.success(c, chunk, 0, 0));
        var corrupt = new ChunkCoordinate(0, -1);
        results.put(corrupt, ChunkLoadResult.withoutChunk(corrupt, ChunkLoadStatus.CORRUPT, "bad chunk", 0, 0));
        var world = Path.of(".").toAbsolutePath();
        return new VoxelAreaLoadResult(world, WorldDimension.overworld(world), rectangle, results);
    }
    @Test void negativePositionsDistinguishRealAirFromMissingCorruptAndUnloaded() throws Exception {
        var context = new ModelBlockContext(area(), new ModelBlockContext.Position(-1, 5, -1), CancellationSignal.NONE);
        assertEquals("unregistered:stone", context.at(0, 0, 0).state().orElseThrow().name());
        assertEquals(BlockState.AIR, context.at(0, 16, 0).state().orElseThrow());
        assertEquals(ChunkLoadStatus.CORRUPT, context.at(1, 0, 0).status());
        assertEquals(ChunkLoadStatus.MISSING, context.at(0, 0, 1).status());
        assertEquals(ChunkLoadStatus.UNSUPPORTED, context.at(32, 0, 0).status());
        assertTrue(context.at(1, 0, 0).state().isEmpty());
        assertEquals("minecraft:overworld", context.dimension());
        assertThrows(IllegalArgumentException.class, () -> context.at(Integer.MIN_VALUE, 0, 0));
    }
    @Test void cancellationAndCoordinateOverflowAreBounded() throws Exception {
        var context = new ModelBlockContext(area(), new ModelBlockContext.Position(Long.MAX_VALUE, Integer.MAX_VALUE, 0), CancellationSignal.NONE);
        assertEquals(ChunkLoadStatus.UNSUPPORTED, context.at(1, 1, 0).status());
        var cancelled = new ModelBlockContext(area(), new ModelBlockContext.Position(0, 0, 0), () -> true);
        assertThrows(java.util.concurrent.CancellationException.class, () -> cancelled.at(0, 0, 0));
    }
}
