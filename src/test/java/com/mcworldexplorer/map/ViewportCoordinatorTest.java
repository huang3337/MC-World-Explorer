package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewportCoordinatorTest {
    @Test
    void separatesVisibleTilesFromOneTilePrefetchMargin() {
        MapViewportState state = new MapViewportState(0, 0, MapZoomLevel.BLOCKS_1);

        List<MapTileKey> keys = new ViewportCoordinator().visibleKeys(
                "world",
                "minecraft:overworld",
                PreviewLayer.surfaceOverview(),
                state,
                512,
                512,
                "v1");

        assertEquals(4, keys.size());
        assertEquals(0, keys.getFirst().tileX());
        assertEquals(0, keys.getFirst().tileZ());
        assertTrue(keys.stream().anyMatch(key -> key.tileX() == -1 && key.tileZ() == -1));

        List<MapTileKey> prefetch = new ViewportCoordinator().prefetchKeys(
                "world",
                "minecraft:overworld",
                PreviewLayer.surfaceOverview(),
                state,
                512,
                512,
                "v1");

        assertEquals(12, prefetch.size());
        assertTrue(prefetch.stream().noneMatch(keys::contains));
        assertTrue(prefetch.stream().anyMatch(
                key -> key.tileX() == -2 && key.tileZ() == -2));
    }

    @Test
    void usesFloorCoordinatesAcrossNegativeOrigin() {
        MapViewportState state = new MapViewportState(-1, -1, MapZoomLevel.BLOCKS_16);

        List<MapTileKey> keys = new ViewportCoordinator().visibleKeys(
                "world",
                "minecraft:overworld",
                PreviewLayer.surfaceOverview(),
                state,
                1,
                1,
                "v1");

        assertEquals(-1, keys.getFirst().tileX());
        assertEquals(-1, keys.getFirst().tileZ());
    }
}
