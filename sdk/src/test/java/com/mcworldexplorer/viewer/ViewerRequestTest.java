package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewerRequestTest {
    private static final ViewerMapReturnState RETURN_STATE =
            new ViewerMapReturnState(10, -20, 2, PreviewLayer.surfaceOverview());

    @Test
    void normalizesStableAreaIdentityWithoutIncludingMapLayer() {
        ViewerRequest request = new ViewerRequest(
                Path.of("world/../world"), "Test", "0", "主世界",
                ChunkRectangle.target(-1, 2, 1, 4),
                ViewerPerformanceProfile.BASIC, RETURN_STATE);

        ViewerAreaKey key = request.areaKey("v06-r1");

        assertEquals(Path.of("world").toAbsolutePath().normalize(), key.world());
        assertEquals("minecraft:overworld", key.dimensionId());
        assertEquals("3 x 3", request.rangeDescription());
        assertEquals(8, request.centerBlockX());
        assertEquals(56, request.centerBlockZ());
    }

    @Test
    void rejectsRangesOutsideSelectedProfile() {
        assertThrows(IllegalArgumentException.class, () -> new ViewerRequest(
                Path.of("world"), "Test", "minecraft:overworld", "主世界",
                ChunkRectangle.target(0, 0, 4, 4),
                ViewerPerformanceProfile.BASIC, RETURN_STATE));
    }

    @Test
    void acceptsRectangularTargets() {
        ViewerRequest request = new ViewerRequest(
                Path.of("world"), "Test", "minecraft:overworld", "主世界",
                ChunkRectangle.target(0, 0, 15, 7),
                ViewerPerformanceProfile.ENHANCED, RETURN_STATE);
        assertEquals("16 x 8", request.rangeDescription());
        assertEquals(128, request.centerBlockX());
        assertEquals(64, request.centerBlockZ());
    }
    @Test
    void validatesRawRectanglesAndAllowsNarrowBasicSelections() {
        new ViewerRequest(Path.of("world"), "Test", "minecraft:overworld", "主世界",
                new ChunkRectangle(-4, 0, 4, 0), ViewerPerformanceProfile.BASIC, RETURN_STATE);
        for (ChunkRectangle invalid : java.util.List.of(
                new ChunkRectangle(0, 0, 32, 0), new ChunkRectangle(0, 0, 16, 15),
                new ChunkRectangle(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, 0))) {
            assertThrows(IllegalArgumentException.class, () -> new ViewerRequest(
                    Path.of("world"), "Test", "minecraft:overworld", "主世界", invalid,
                    ViewerPerformanceProfile.ENHANCED, RETURN_STATE));
            assertThrows(IllegalArgumentException.class, () -> new com.mcworldexplorer.voxel.io.VoxelAreaRequest(
                    Path.of("world"), "minecraft:overworld", invalid));
        }
    }

}
