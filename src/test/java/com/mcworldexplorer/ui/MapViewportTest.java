package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapDisplayZoom;
import com.mcworldexplorer.map.MapTileKey;
import com.mcworldexplorer.map.MapZoomLevel;
import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapViewportTest {
    private static final MapTileKey KEY = new MapTileKey(
            "world",
            "minecraft:overworld",
            PreviewLayer.surfaceOverview(),
            MapZoomLevel.BLOCKS_2,
            0,
            0,
            "test");

    @Test
    void retryClickRequiresTheSameTileAndAtMostFourPixelsOfMovement() {
        assertTrue(MapViewport.isRetryClick(KEY, KEY, 2, 3));
        assertTrue(MapViewport.isRetryClick(KEY, KEY, 4, 0));
        assertFalse(MapViewport.isRetryClick(KEY, KEY, 4, 1));
    }

    @Test
    void retryClickRejectsMissingOrDifferentTiles() {
        MapTileKey other = new MapTileKey(
                "world",
                "minecraft:overworld",
                PreviewLayer.surfaceOverview(),
                MapZoomLevel.BLOCKS_2,
                1,
                0,
                "test");

        assertFalse(MapViewport.isRetryClick(null, KEY, 0, 0));
        assertFalse(MapViewport.isRetryClick(KEY, null, 0, 0));
        assertFalse(MapViewport.isRetryClick(KEY, other, 0, 0));
    }

    @Test
    void settledMaximumZoomDoesNotStartAnotherZoomChange() {
        assertTrue(MapViewport.isSettledAtZoom(
                MapDisplayZoom.PIXELS_4,
                0.25,
                MapDisplayZoom.PIXELS_4));
        assertFalse(MapViewport.isSettledAtZoom(
                MapDisplayZoom.PIXELS_4,
                0.5,
                MapDisplayZoom.PIXELS_4));
        assertFalse(MapViewport.isSettledAtZoom(
                MapDisplayZoom.PIXELS_2,
                0.5,
                MapDisplayZoom.PIXELS_4));
    }
}
