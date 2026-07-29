package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapCoordinateModelTest {
    @Test
    void supportsOnlyConfirmedZoomLevels() {
        assertEquals(1, MapZoomLevel.BLOCKS_1.blocksPerPixel());
        assertEquals(16, MapZoomLevel.BLOCKS_16.blocksPerPixel());
        assertEquals(MapZoomLevel.BLOCKS_1, MapZoomLevel.BLOCKS_1.zoomIn());
        assertEquals(MapZoomLevel.BLOCKS_16, MapZoomLevel.BLOCKS_16.zoomOut());
        assertEquals(MapZoomLevel.BLOCKS_4, MapZoomLevel.fromBlocksPerPixel(4));
        assertEquals(MapZoomLevel.BLOCKS_4, MapZoomLevel.nearest(3.2));
        assertEquals(MapZoomLevel.BLOCKS_8, MapZoomLevel.nearest(6.8));
        assertThrows(IllegalArgumentException.class, () -> MapZoomLevel.fromBlocksPerPixel(3));
    }

    @Test
    void usesFloorDivisionAcrossNegativeTileBoundaries() {
        assertEquals(0, MapTileKey.tileCoordinate(0, MapZoomLevel.BLOCKS_1));
        assertEquals(0, MapTileKey.tileCoordinate(255, MapZoomLevel.BLOCKS_1));
        assertEquals(-1, MapTileKey.tileCoordinate(-1, MapZoomLevel.BLOCKS_1));
        assertEquals(-1, MapTileKey.tileCoordinate(-256, MapZoomLevel.BLOCKS_1));
        assertEquals(-2, MapTileKey.tileCoordinate(-257, MapZoomLevel.BLOCKS_1));
    }

    @Test
    void computesBoundsForEachZoom() {
        MapTileBounds bounds = MapTileBounds.of(-2, 3, MapZoomLevel.BLOCKS_4);

        assertEquals(-2048, bounds.minX());
        assertEquals(3072, bounds.minZ());
        assertEquals(-1024, bounds.maxXExclusive());
        assertEquals(4096, bounds.maxZExclusive());
        assertEquals(1024, bounds.blockWidth());
    }

    @Test
    void tileKeyCarriesLayerAndProducesBounds() {
        MapTileKey key = new MapTileKey(
                "world-id",
                "minecraft:overworld",
                PreviewLayer.heightBand(64, 95),
                MapZoomLevel.BLOCKS_2,
                1,
                -1,
                "0.3-tile-1");

        assertEquals(512, key.bounds().minX());
        assertEquals(-512, key.bounds().minZ());
        assertEquals("y-64-95", key.layer().cacheKey());
    }

    @Test
    void keepsPointerWorldCoordinateStableWhileZooming() {
        MapViewportState state = new MapViewportState(100, -50, MapZoomLevel.BLOCKS_2);
        double anchorX = state.worldXAt(640, 800);
        double anchorZ = state.worldZAt(120, 600);

        state.zoomAt(MapZoomLevel.BLOCKS_4, 640, 120, 800, 600);

        assertEquals(anchorX, state.worldXAt(640, 800), 0.000001);
        assertEquals(anchorZ, state.worldZAt(120, 600), 0.000001);
    }

    @Test
    void pansAndResetsWithoutClamping() {
        MapViewportState state = new MapViewportState(10, 20, MapZoomLevel.BLOCKS_1);

        state.panPixels(1000, -500);
        assertEquals(-990, state.centerX(), 0.000001);
        assertEquals(520, state.centerZ(), 0.000001);

        state.reset();
        assertEquals(10, state.centerX(), 0.000001);
        assertEquals(20, state.centerZ(), 0.000001);
        assertEquals(MapZoomLevel.BLOCKS_1, state.zoom());
        assertEquals(1, state.visualBlocksPerPixel(), 0.000001);
    }

    @Test
    void keepsPointerStableDuringContinuousVisualZoomAndCommitsSeparately() {
        MapViewportState state = new MapViewportState(100, -50, MapZoomLevel.BLOCKS_4);
        double anchorX = state.worldXAt(700, 900);
        double anchorZ = state.worldZAt(100, 500);

        state.zoomVisualAt(2.75, 700, 100, 900, 500);

        assertEquals(anchorX, state.worldXAt(700, 900), 0.000001);
        assertEquals(anchorZ, state.worldZAt(100, 500), 0.000001);
        assertEquals(2.75, state.visualBlocksPerPixel(), 0.000001);
        assertEquals(MapZoomLevel.BLOCKS_4, state.zoom());

        state.commitZoom(MapZoomLevel.nearest(state.visualBlocksPerPixel()));
        assertEquals(MapZoomLevel.BLOCKS_2, state.zoom());
        assertEquals(2.75, state.visualBlocksPerPixel(), 0.000001);
    }

    @Test
    void setViewUsesRequestedCenterAndExactZoomWithoutChangingResetDefaults() {
        MapViewportState state = new MapViewportState(10, 20, MapZoomLevel.BLOCKS_4);

        state.setView(-713.7, -602.2, MapZoomLevel.BLOCKS_2);

        assertEquals(-713.7, state.centerX());
        assertEquals(-602.2, state.centerZ());
        assertEquals(MapZoomLevel.BLOCKS_2, state.zoom());
        assertEquals(2, state.visualBlocksPerPixel());

        state.reset();
        assertEquals(10, state.centerX());
        assertEquals(20, state.centerZ());
        assertEquals(MapZoomLevel.BLOCKS_4, state.zoom());
    }
}
