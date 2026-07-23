package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewRequestResolverTest {
    private static final DimensionHeightRange OVERWORLD_RANGE = new DimensionHeightRange(-64, 319);
    private static final DimensionHeightRange NETHER_RANGE = new DimensionHeightRange(0, 255);

    @Test
    void preservesOverworldRespawnCenterAndUsesPlayerHeightBand() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerRespawnPosition(120, 70, -80, WorldDimension.OVERWORLD_ID);
        world.setPlayerPosition(8.5, -20.2, 10.5, WorldDimension.OVERWORLD_ID);

        PreviewRequest request = PreviewRequestResolver.resolve(
                world,
                WorldDimension.overworld(world.getFolderPath()),
                OVERWORLD_RANGE,
                null);

        assertEquals(new PreviewCenter(120, -80, PreviewCenterSource.PLAYER_RESPAWN), request.center());
        assertEquals(PreviewLayer.heightBand(-32, -1), request.layer());
    }

    @Test
    void usesCurrentPlayerCoordinatesOnlyForMatchingNonOverworldDimension() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerPosition(-12.8, 72.4, 31.9, WorldDimension.NETHER_ID);
        WorldDimension nether = WorldDimension.nether(world.getFolderPath());

        PreviewRequest request = PreviewRequestResolver.resolve(world, nether, NETHER_RANGE, null);

        assertEquals(new PreviewCenter(-13, 31, PreviewCenterSource.PLAYER_POSITION), request.center());
        assertEquals(PreviewLayer.heightBand(64, 95), request.layer());
    }

    @Test
    void defaultsNetherToOriginAndY64WithoutInventingHistory() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerPosition(100, 30, 200, WorldDimension.OVERWORLD_ID);

        PreviewRequest request = PreviewRequestResolver.resolve(
                world,
                WorldDimension.nether(world.getFolderPath()),
                NETHER_RANGE,
                null);

        assertEquals(new PreviewCenter(0, 0, PreviewCenterSource.DIMENSION_ORIGIN), request.center());
        assertEquals(PreviewLayer.heightBand(64, 95), request.layer());
    }

    @Test
    void respectsExplicitLayerSelection() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        PreviewLayer selected = PreviewLayer.heightBand(0, 31);

        PreviewRequest request = PreviewRequestResolver.resolve(
                world,
                WorldDimension.overworld(world.getFolderPath()),
                OVERWORLD_RANGE,
                selected);

        assertEquals(selected, request.layer());
    }

    @Test
    void ignoresNonFinitePlayerPosition() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerPosition(Double.NaN, 70, 10, WorldDimension.NETHER_ID);

        PreviewRequest request = PreviewRequestResolver.resolve(
                world,
                WorldDimension.nether(world.getFolderPath()),
                NETHER_RANGE,
                null);

        assertEquals(PreviewCenterSource.DIMENSION_ORIGIN, request.center().source());
        assertEquals(PreviewLayer.heightBand(64, 95), request.layer());
    }

    @Test
    void doesNotTreatMissingPlayerDimensionAsOverworld() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerPosition(10, 70, 20, null);

        PreviewRequest missingDimension = PreviewRequestResolver.resolve(
                world,
                WorldDimension.overworld(world.getFolderPath()),
                OVERWORLD_RANGE,
                null);
        world.setPlayerPosition(10, 70, 20, "");
        PreviewRequest blankDimension = PreviewRequestResolver.resolve(
                world,
                WorldDimension.overworld(world.getFolderPath()),
                OVERWORLD_RANGE,
                null);

        assertEquals(PreviewLayer.surfaceOverview(), missingDimension.layer());
        assertEquals(PreviewLayer.surfaceOverview(), blankDimension.layer());
    }

    @Test
    void stillMapsLegacyNumericOverworldDimension() {
        WorldInfo world = new WorldInfo(Path.of("world"));
        world.setPlayerPosition(10, 70, 20, "0");

        PreviewRequest request = PreviewRequestResolver.resolve(
                world,
                WorldDimension.overworld(world.getFolderPath()),
                OVERWORLD_RANGE,
                null);

        assertEquals(PreviewLayer.heightBand(64, 95), request.layer());
    }
}
