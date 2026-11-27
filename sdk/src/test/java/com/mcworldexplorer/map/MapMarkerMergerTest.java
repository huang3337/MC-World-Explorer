package com.mcworldexplorer.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapMarkerMergerTest {
    @Test
    void mergesPortalBoundsThatTouchAcrossTileBoundary() {
        MapMarker first = MapMarker.portal("minecraft:overworld", 254, 60, 10, 255, 63, 10);
        MapMarker second = MapMarker.portal("minecraft:overworld", 256, 60, 10, 257, 63, 10);

        List<MapMarker> merged = MapMarkerMerger.mergePortals(List.of(first, second));

        assertEquals(1, merged.size());
        assertEquals(254, merged.getFirst().minX());
        assertEquals(257, merged.getFirst().maxX());
    }

    @Test
    void keepsSeparatedPortalsAndNonPortalMarkers() {
        List<MapMarker> merged = MapMarkerMerger.mergePortals(List.of(
                MapMarker.portal("minecraft:overworld", 0, 60, 0, 1, 63, 0),
                MapMarker.portal("minecraft:overworld", 10, 60, 0, 11, 63, 0),
                MapMarker.point(MapMarkerType.PLAYER, "minecraft:overworld", 0, 64, 0)));

        assertEquals(3, merged.size());
    }
}
