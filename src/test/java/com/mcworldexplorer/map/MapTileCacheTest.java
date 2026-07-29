package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTileCacheTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(HOME_PROPERTY);

    @AfterEach
    void restoreHome() {
        if (originalHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void storesAndReusesTileAcrossCacheInstances() throws IOException {
        Fixture fixture = fixture();
        MapTileCache first = new MapTileCache();

        MapTileCacheResult stored = first.store(
                fixture.world, fixture.dimension, fixture.key, image());

        assertTrue(stored.imagePath().startsWith(
                WorldCachePaths.worldDirectory(fixture.world).resolve("map-v03")));
        assertTrue(new MapTileCache().findReusable(
                fixture.world, fixture.dimension, fixture.key).isPresent());
    }

    @Test
    void reusesPortalMarkersWithCachedTile() throws IOException {
        Fixture fixture = fixture();
        MapTileGenerationResult generation = new MapTileGenerationResult(
                image(),
                List.of(MapMarker.portal(
                        fixture.dimension.id(),
                        10, 60, 20,
                        11, 63, 20)),
                1, 1, 0, 0, 4, 0);

        new MapTileCache().store(
                fixture.world,
                fixture.dimension,
                fixture.key,
                generation);

        MapTileCacheResult reused = new MapTileCache().findReusable(
                fixture.world,
                fixture.dimension,
                fixture.key).orElseThrow();
        assertEquals(generation.markers(), reused.markers());
    }

    @Test
    void invalidatesWhenCoveredRegionIsAddedOrChanged() throws IOException {
        Fixture fixture = fixture();
        MapTileCache cache = new MapTileCache();
        cache.store(fixture.world, fixture.dimension, fixture.key, image());

        Files.write(fixture.dimension.regionDirectory().resolve("r.0.0.mca"), new byte[]{1, 2, 3});

        assertTrue(cache.findReusable(
                fixture.world, fixture.dimension, fixture.key).isEmpty());
    }

    @Test
    void ignoresChangesOutsideTileBounds() throws IOException {
        Fixture fixture = fixture();
        MapTileCache cache = new MapTileCache();
        cache.store(fixture.world, fixture.dimension, fixture.key, image());

        Files.write(fixture.dimension.regionDirectory().resolve("r.8.8.mca"), new byte[]{1});

        assertTrue(cache.findReusable(
                fixture.world, fixture.dimension, fixture.key).isPresent());
    }

    @Test
    void removesCorruptPngInsteadOfRepeatedlyHittingIt() throws IOException {
        Fixture fixture = fixture();
        MapTileCache cache = new MapTileCache();
        MapTileCacheResult stored = cache.store(
                fixture.world, fixture.dimension, fixture.key, image());
        Files.writeString(stored.imagePath(), "not a png");

        assertTrue(cache.findReusable(
                fixture.world, fixture.dimension, fixture.key).isEmpty());
        assertFalse(Files.exists(stored.imagePath()));
        assertFalse(Files.exists(stored.metadataPath()));
    }

    private Fixture fixture() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.resolve("app").toString());
        Path worldFolder = Files.createDirectories(tempDir.resolve("world"));
        Files.writeString(worldFolder.resolve("level.dat"), "level");
        Path regionDirectory = Files.createDirectories(worldFolder.resolve("region"));
        WorldInfo world = new WorldInfo(worldFolder);
        world.setLevelName("Cache World");
        WorldDimension dimension = WorldDimension.overworld(worldFolder);
        MapTileKey key = new MapTileKey(
                WorldCachePaths.worldDirectoryName(world),
                dimension.id(),
                PreviewLayer.surfaceOverview(),
                MapZoomLevel.BLOCKS_1,
                0,
                0,
                "0.3-tile-1");
        return new Fixture(world, dimension, key, regionDirectory);
    }

    private static BufferedImage image() {
        return new BufferedImage(
                MapTileBounds.TILE_PIXELS,
                MapTileBounds.TILE_PIXELS,
                BufferedImage.TYPE_INT_ARGB);
    }

    private record Fixture(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            Path regionDirectory) {
    }
}
