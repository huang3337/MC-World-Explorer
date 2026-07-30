package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void supportsHighMagnificationDisplayZoomsWithoutNewTileZooms() {
        assertEquals(MapDisplayZoom.BLOCKS_8, MapDisplayZoom.BLOCKS_16.zoomIn());
        assertEquals(MapDisplayZoom.BLOCKS_1, MapDisplayZoom.BLOCKS_2.zoomIn());
        assertEquals(MapDisplayZoom.PIXELS_2, MapDisplayZoom.BLOCKS_1.zoomIn());
        assertEquals(MapDisplayZoom.PIXELS_4, MapDisplayZoom.PIXELS_2.zoomIn());
        assertEquals(MapDisplayZoom.PIXELS_4, MapDisplayZoom.PIXELS_4.zoomIn());
        assertEquals(MapDisplayZoom.BLOCKS_16, MapDisplayZoom.BLOCKS_16.zoomOut());
        assertEquals(MapZoomLevel.BLOCKS_1, MapDisplayZoom.PIXELS_2.tileZoom());
        assertEquals(MapZoomLevel.BLOCKS_1, MapDisplayZoom.PIXELS_4.tileZoom());
        assertEquals(0.5, MapDisplayZoom.PIXELS_2.blocksPerPixel());
        assertEquals(0.25, MapDisplayZoom.PIXELS_4.blocksPerPixel());
        assertEquals(MapDisplayZoom.PIXELS_4, MapDisplayZoom.nearest(0.3));
        assertEquals(MapDisplayZoom.BLOCKS_4, MapDisplayZoom.fromTileZoom(MapZoomLevel.BLOCKS_4));
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
    void highMagnificationKeepsPointerStableAndUsesBlockOneTiles() {
        MapViewportState state = new MapViewportState(100, -50, MapZoomLevel.BLOCKS_1);
        double anchorX = state.worldXAt(700, 900);
        double anchorZ = state.worldZAt(100, 500);

        state.zoomVisualAt(0.25, 700, 100, 900, 500);
        state.commitZoom(MapDisplayZoom.PIXELS_4);

        assertEquals(anchorX, state.worldXAt(700, 900), 0.000001);
        assertEquals(anchorZ, state.worldZAt(100, 500), 0.000001);
        assertEquals(MapDisplayZoom.PIXELS_4, state.displayZoom());
        assertEquals(MapZoomLevel.BLOCKS_1, state.zoom());
        assertEquals(0.25, state.visualBlocksPerPixel(), 0.000001);

        ViewportCoordinator coordinator = new ViewportCoordinator();
        assertTrue(coordinator.visibleKeys(
                        "world",
                        "minecraft:overworld",
                        PreviewLayer.surfaceOverview(),
                        state,
                        800,
                        600,
                        "test").stream()
                .allMatch(key -> key.zoom() == MapZoomLevel.BLOCKS_1));
    }

    @Test
    void rejectsVisualZoomOutsideConfirmedRange() {
        MapViewportState state = new MapViewportState(0, 0, MapZoomLevel.BLOCKS_1);

        assertThrows(IllegalArgumentException.class,
                () -> state.zoomVisualAt(0.249, 0, 0, 800, 600));
        assertThrows(IllegalArgumentException.class,
                () -> state.zoomVisualAt(16.001, 0, 0, 800, 600));
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
