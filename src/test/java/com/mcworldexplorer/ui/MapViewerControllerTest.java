package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarkerType;
import com.mcworldexplorer.preview.DimensionHeightRange;
import com.mcworldexplorer.preview.PreviewRequest;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.PlayerLocation;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapViewerControllerTest {
    @Test
    void initialMapRequestAlwaysUsesSurfaceOverview() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerPosition(12, 70, -34, WorldDimension.OVERWORLD_ID);

        PreviewRequest request = MapViewerController.initialMapRequest(
                world,
                WorldDimension.overworld(world.getFolderPath()),
                new DimensionHeightRange(-64, 319));

        assertTrue(request.layer().isSurfaceOverview());
        assertEquals(0, request.center().x());
        assertEquals(0, request.center().z());
    }

    @Test
    void markerStylesUseTheSameThreeColorsAsTheViewport() {
        assertEquals("#44c767", MapMarkerStyle.hex(MapMarkerType.PLAYER));
        assertEquals("#f2c94c", MapMarkerStyle.hex(MapMarkerType.WORLD_SPAWN));
        assertEquals("#bd5cff", MapMarkerStyle.hex(MapMarkerType.NETHER_PORTAL));
    }

    @Test
    void playerNavigationAlwaysUsesSurfaceAndDefaultZoom() {
        PlayerLocation player = new PlayerLocation(
                UUID.fromString("5b35922a-b98e-4cef-b229-ca5890e979b6"),
                "huang3337",
                "callfromthedepth_:depth",
                1623.4,
                46,
                -2528.1,
                1);

        MapViewerController.PlayerNavigation navigation =
                MapViewerController.navigationFor(player);

        assertEquals("callfromthedepth_:depth", navigation.dimensionId());
        assertTrue(navigation.layer().isSurfaceOverview());
        assertEquals(com.mcworldexplorer.map.MapZoomLevel.BLOCKS_2, navigation.zoom());
        assertEquals(1623.4, navigation.x());
        assertEquals(-2528.1, navigation.z());
    }

    @Test
    void emptyMarkerTooltipHasNoDisplayText() {
        assertTrue(MapViewport.markerTooltipText(null).isEmpty());
    }
}
