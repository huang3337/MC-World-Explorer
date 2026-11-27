package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.*;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MapSummaryPipelineTest {
    @TempDir Path worldPath;

    private MapTileKey key(WorldDimension dimension, int zoom, long x, long z, PreviewLayer layer) {
        return new MapTileKey(WorldCachePaths.worldDirectoryName(new WorldInfo(worldPath)), dimension.id(),
                layer, MapZoomLevel.fromBlocksPerPixel(zoom), x, z, "p2-test");
    }

    @Test void repeatedFiveLevelZoomReusesValidTerrainWithoutDecodingAgain() throws Exception {
        MapSamplingFixtures.region(worldPath.resolve("region/r.0.0.mca"), "minecraft:stone", 3, 1, 0, 1, 32, 33);
        // Empty occupied file is not terrain and must not poison any coarser view.
        Files.write(worldPath.resolve("region/r.1.0.mca"), new byte[0]);
        var generator = new MapTileGenerator();
        var dimension = WorldDimension.overworld(worldPath);
        var world = new WorldInfo(worldPath);
        for (int zoom : new int[]{1, 2, 4, 8, 16, 8, 4, 2, 1}) {
            var result = generator.generate(world, dimension, key(dimension, zoom, 0, 0,
                    PreviewLayer.surfaceOverview()), MapTileGenerationMonitor.NONE);
            assertEquals(4, generator.decodeCount());
            assertEquals(0, result.failedChunks());
            assertTrue(result.unreadable().isEmpty());
        }
    }

    @Test void reusesAcrossZoomsAndPreservesPixelsIdentityAndLayerSemantics() throws Exception {
        Path region = worldPath.resolve("region/r.0.0.mca");
        MapSamplingFixtures.region(region, "example:unmapped", -1, 1, 0, 1, 32, 33);
        var dimension = WorldDimension.overworld(worldPath);
        var world = new WorldInfo(worldPath);
        var generator = new MapTileGenerator();
        for (int zoom : new int[]{1, 2}) {
            var key = key(dimension, zoom, 0, 0, PreviewLayer.surfaceOverview());
            var actual = generator.generate(world, dimension, key, MapTileGenerationMonitor.NONE);
            assertEquals(4, actual.sampledChunks());
            assertEquals(0, actual.failedChunks());
            assertEquals(actual.totalChunks() - 4, actual.missingChunks());
            assertEquals(1024, actual.unknownBlockColumns());
            var expected = new MapTileRenderer();
            try (var reader = new RegionFileReader(region)) {
                for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
                    var surface = new SurfaceSampler().sample(reader.readChunk(x, z).orElseThrow());
                    for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        var column = surface.getColumn(lx, lz).orElseThrow();
                        expected.accept(key.bounds(), key.zoom(), x * 16L + lx, z * 16L + lz,
                                BlockColorPalette.resolve(column.blockName()).rgb(), column.y());
                    }
                }
            }
            assertArrayEquals(expected.render().getRGB(0, 0, 256, 256, null, 0, 256),
                    actual.image().getRGB(0, 0, 256, 256, null, 0, 256));
        }
        assertEquals(4, generator.summaryStatistics().loads());
        assertEquals(4, generator.summaryStatistics().hits());
        var band = generator.generate(world, dimension,
                key(dimension, 1, 0, 0, PreviewLayer.heightBand(-16, -9)), MapTileGenerationMonitor.NONE);
        assertEquals(0, band.populatedColumns()); // 实心段内部没有两格净空，不能复用地表列。
        assertEquals(8, generator.summaryStatistics().loads());
    }

    @Test void concurrentDifferentZoomRequestsOnlyLoadEachChunkOnce() throws Exception {
        MapSamplingFixtures.region(worldPath.resolve("region/r.0.0.mca"), "minecraft:stone", 0, 1, 0, 1, 32, 33);
        var generator = new MapTileGenerator();
        var dimension = WorldDimension.overworld(worldPath);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var fine = pool.submit(() -> { start.await(); return generator.generate(new WorldInfo(worldPath), dimension,
                    key(dimension, 1, 0, 0, PreviewLayer.surfaceOverview()), MapTileGenerationMonitor.NONE); });
            var coarse = pool.submit(() -> { start.await(); return generator.generate(new WorldInfo(worldPath), dimension,
                    key(dimension, 2, 0, 0, PreviewLayer.surfaceOverview()), MapTileGenerationMonitor.NONE); });
            start.countDown();
            assertEquals(4, fine.get(10, TimeUnit.SECONDS).sampledChunks());
            assertEquals(4, coarse.get(10, TimeUnit.SECONDS).sampledChunks());
        }
        assertEquals(4, generator.summaryStatistics().loads());
        assertEquals(4, generator.summaryStatistics().hits() + generator.summaryStatistics().joins());
    }

    @Test void sourceHeaderChangeInvalidatesSummaryEvenWhenSizeAndTimeAreUnchanged() throws Exception {
        Path region = worldPath.resolve("region/r.0.0.mca");
        MapSamplingFixtures.region(region, "minecraft:stone", 0, 1, 0);
        var modified = Files.getLastModifiedTime(region);
        var dimension = WorldDimension.overworld(worldPath);
        var key = key(dimension, 1, 0, 0, PreviewLayer.surfaceOverview());
        var generator = new MapTileGenerator();
        var before = generator.generate(new WorldInfo(worldPath), dimension, key, MapTileGenerationMonitor.NONE);
        MapSamplingFixtures.region(region, "minecraft:dirt", 0, 2, 0);
        Files.setLastModifiedTime(region, modified);
        var after = generator.generate(new WorldInfo(worldPath), dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(2, generator.summaryStatistics().loads());
        assertNotEquals(before.image().getRGB(0, 0), after.image().getRGB(0, 0));
    }

    @Test void rejectsSourceMutationDuringProgressInsteadOfPublishingMixedTile() throws Exception {
        Path region = worldPath.resolve("region/r.0.0.mca");
        MapSamplingFixtures.region(region, "minecraft:stone", 0, 1, 0, 1);
        var changed = new AtomicBoolean();
        var dimension = WorldDimension.overworld(worldPath);
        var monitor = new MapTileGenerationMonitor() {
            public void onPartial(MapTilePartialResult ignored) {
                if (changed.compareAndSet(false, true)) try {
                    MapSamplingFixtures.region(region, "minecraft:dirt", 0, 2, 0, 1);
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        };
        assertThrows(IOException.class, () -> new MapTileGenerator().generate(new WorldInfo(worldPath), dimension,
                key(dimension, 1, 0, 0, PreviewLayer.surfaceOverview()), monitor));
        assertTrue(changed.get());
    }

    @Test void handlesNegativeCoordinatesAndPreservesPortalMarkers() throws Exception {
        MapSamplingFixtures.region(worldPath.resolve("region/r.-1.-1.mca"), "minecraft:nether_portal", -1, 1, 1023);
        var dimension = WorldDimension.overworld(worldPath);
        var generator = new MapTileGenerator();
        var fine = generator.generate(new WorldInfo(worldPath), dimension,
                key(dimension, 1, -1, -1, PreviewLayer.surfaceOverview()), MapTileGenerationMonitor.NONE);
        var coarse = generator.generate(new WorldInfo(worldPath), dimension,
                key(dimension, 2, -1, -1, PreviewLayer.surfaceOverview()), MapTileGenerationMonitor.NONE);
        assertEquals(1, fine.sampledChunks());
        assertFalse(fine.markers().isEmpty());
        assertEquals(fine.markers(), coarse.markers());
        assertEquals(1, generator.summaryStatistics().loads());
    }

    @Test void distinguishesEmptySpaceFromTruncatedRegionAndServiceRefusesToCacheFailure() throws Exception {
        var dimension = WorldDimension.overworld(worldPath);
        var key = key(dimension, 1, 0, 0, PreviewLayer.surfaceOverview());
        var generator = new MapTileGenerator();
        var empty = generator.generate(new WorldInfo(worldPath), dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(256, empty.missingChunks());
        Files.createDirectories(dimension.regionDirectory());
        Files.write(dimension.regionDirectory().resolve("r.0.0.mca"), new byte[99]);
        var damaged = generator.generate(new WorldInfo(worldPath), dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(256, damaged.failedChunks());
        assertEquals(0, damaged.missingChunks());
        assertThrows(IOException.class, () -> new MapTileService().load(new WorldInfo(worldPath), dimension,
                key, MapTileGenerationMonitor.NONE));
        assertEquals(0, generator.summaryStatistics().loads());
    }

    @Test void clearingGeneratorForcesResampleAndCancelledRequestDoesNotRead() throws Exception {
        MapSamplingFixtures.region(worldPath.resolve("region/r.0.0.mca"), "minecraft:stone", 0, 1, 0);
        var dimension = WorldDimension.overworld(worldPath);
        var key = key(dimension, 1, 0, 0, PreviewLayer.surfaceOverview());
        var generator = new MapTileGenerator();
        assertThrows(CancellationException.class, () -> generator.generate(new WorldInfo(worldPath), dimension, key,
                new MapTileGenerationMonitor() { public boolean isCancelled() { return true; } }));
        assertEquals(0, generator.summaryStatistics().loads());
        generator.generate(new WorldInfo(worldPath), dimension, key, MapTileGenerationMonitor.NONE);
        generator.clearMemory();
        generator.generate(new WorldInfo(worldPath), dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(2, generator.summaryStatistics().loads());
    }

    @Test void dimensionIdentitySeparatesSummariesEvenWithSameRegionDirectory() throws Exception {
        MapSamplingFixtures.region(worldPath.resolve("region/r.0.0.mca"), "minecraft:stone", 0, 1, 0);
        var overworld = WorldDimension.overworld(worldPath);
        var other = new WorldDimension("example:dimension", "Other", overworld.regionDirectory(), DimensionKind.MOD);
        var generator = new MapTileGenerator();
        for (var dimension : new WorldDimension[]{overworld, other}) {
            assertEquals(1, generator.generate(new WorldInfo(worldPath), dimension,
                    key(dimension, 1, 0, 0, PreviewLayer.surfaceOverview()),
                    MapTileGenerationMonitor.NONE).sampledChunks());
        }
        assertEquals(2, generator.summaryStatistics().loads());
    }
}
