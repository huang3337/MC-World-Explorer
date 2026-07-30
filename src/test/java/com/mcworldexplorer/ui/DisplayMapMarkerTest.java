package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayMapMarkerTest {
    @Test
    void playerTooltipContainsIdentityDimensionAndPreciseCoordinates() {
        DisplayMapMarker marker = DisplayMapMarker.player(
                MapMarker.point(
                        MapMarkerType.PLAYER,
                        "minecraft:overworld",
                        12,
                        64,
                        -35),
                "5b35922a-b98e-4cef-b229-ca5890e979b6",
                "Alex",
                "主世界",
                12.34,
                64.01,
                -34.56);

        assertTrue(marker.isPlayer());
        assertEquals(
                "Alex · 主世界 (minecraft:overworld) · X 12.3 · Y 64.0 · Z -34.6",
                marker.tooltipText());
    }

    @Test
    void standardMarkerKeepsExistingTooltipFormat() {
        DisplayMapMarker marker = DisplayMapMarker.standard(MapMarker.point(
                MapMarkerType.NETHER_PORTAL,
                "minecraft:overworld",
                8,
                70,
                -4));

        assertFalse(marker.isPlayer());
        assertEquals("下界传送门 · X 8 · Z -4", marker.tooltipText());
    }

    @Test
    void rejectsIncompletePlayerIdentity() {
        MapMarker marker = MapMarker.point(
                MapMarkerType.PLAYER,
                "minecraft:overworld",
                0,
                64,
                0);

        assertThrows(IllegalArgumentException.class, () ->
                DisplayMapMarker.player(marker, "", "Alex", "主世界", 0, 64, 0));
    }
}
