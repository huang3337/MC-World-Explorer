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
        assertEquals(3, request.rangePreset().chunks());
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
    void rejectsNonSquareTargets() {
        assertThrows(IllegalArgumentException.class, () -> new ViewerRequest(
                Path.of("world"), "Test", "minecraft:overworld", "主世界",
                ChunkRectangle.target(0, 0, 2, 1),
                ViewerPerformanceProfile.ENHANCED, RETURN_STATE));
    }
}
