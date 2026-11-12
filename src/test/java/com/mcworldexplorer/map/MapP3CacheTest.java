package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.*;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class MapP3CacheTest {
    static { org.slf4j.LoggerFactory.getLogger(MapP3CacheTest.class); }
    @TempDir Path temp;
    String oldHome;
    WorldInfo world;
    WorldDimension dimension;
    @BeforeEach void setup() throws Exception {
        oldHome = System.getProperty("mcworldexplorer.home");
        System.setProperty("mcworldexplorer.home", temp.resolve("app").toString());
        Path folder = Files.createDirectories(temp.resolve("world"));
        world = new WorldInfo(folder); dimension = WorldDimension.overworld(folder);
    }
    @AfterEach void restore() {
        if (oldHome == null) System.clearProperty("mcworldexplorer.home");
        else System.setProperty("mcworldexplorer.home", oldHome);
    }
    MapTileKey key(int bpp, long x, long z) {
        return new MapTileKey(WorldCachePaths.worldDirectoryName(world), dimension.id(),
                PreviewLayer.surfaceOverview(), MapZoomLevel.fromBlocksPerPixel(bpp), x, z, "p3-test");
    }
    Path region(int x, int z) { return dimension.regionDirectory().resolve("r." + x + "." + z + ".mca"); }
    MapTileGenerationResult generate(MapTileGenerator g, int bpp) throws Exception {
        return g.generate(world, dimension, key(bpp, 0, 0), MapTileGenerationMonitor.NONE, true);
    }
    @Test void duplicateQueuedPageDoesNotRewritePersistedSlots() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        var source = com.mcworldexplorer.region.RegionHeaderIndex.read(region(0, 0)).signature();
        var key = new SurfaceSummaryCache.Key(world.getFolderPath().toRealPath(), dimension.id(), 0, 0,
                PreviewLayer.surfaceOverview(), source, "duplicate");
        var value = MapSamplingFixtures.summary("minecraft:stone", 0);
        try (var first = new SurfaceSummaryStore(world, () -> true);
             var second = new SurfaceSummaryStore(world, () -> true)) {
            first.put(key, value); second.put(key, value); first.close();
            Path file;
            try (var files = Files.list(MapCacheStorage.root(world).resolve("summaries"))) { file = files.findFirst().orElseThrow(); }
            var sentinel = java.nio.file.attribute.FileTime.fromMillis(1000);
            Files.setLastModifiedTime(file, sentinel); second.close();
            assertEquals(sentinel, Files.getLastModifiedTime(file));
        }
    }

    @Test void streamingSummaryLimitCountsUncompressedBytes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new SurfaceSummaryStore.LimitedOutput(bytes, 3)) {
            out.write(new byte[]{1,2}); out.write(3);
            assertThrows(MapPersistence.LimitExceeded.class, () -> out.write(4));
            assertThrows(MapPersistence.LimitExceeded.class, () -> out.write(new byte[]{5,6}));
        }
        assertArrayEquals(new byte[]{1,2,3}, bytes.toByteArray());
    }
    @Test void pyramidMatchesDirectAggregationForAllFiveLevelsWithHolesAndNegativeHeights() throws Exception {
        var bytes = new ByteArrayOutputStream();
        int[] rgb = new int[256], heights = new int[256];
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeUTF("unknown:retained");
            for (int i = 0; i < 256; i++) {
                rgb[i] = (i * 7919) & 0xffffff; heights[i] = i % 17 - 80;
                out.writeShort(i % 3 == 0 ? -1 : 0); out.writeInt(rgb[i]); out.writeInt(heights[i]);
                out.writeBoolean(i % 3 != 0);
            }
            out.writeUTF("marker diagnostic"); out.writeInt(0);
        }
        var summary = new ChunkSurfaceSummary(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("unknown:retained", summary.blockId(1));
        assertEquals("UNKNOWN_2D_COLOR", summary.colorDiagnostic(1));
        assertEquals("marker diagnostic", summary.markerDiagnostic());
        for (var zoom : MapZoomLevel.values()) {
            var bounds = MapTileBounds.of(-1, -1, zoom);
            var expected = new MapTileRenderer(); var actual = new MapTileRenderer();
            int cx = -16 * zoom.blocksPerPixel(), cz = cx;
            for (int i = 0; i < 256; i++) if (i % 3 != 0)
                expected.accept(bounds, zoom, cx * 16L + i % 16, cz * 16L + i / 16, rgb[i], heights[i]);
            summary.renderInto(actual, bounds, zoom, cx, cz);
            assertArrayEquals(pixels(expected.render()), pixels(actual.render()));
            assertEquals(0, actual.renderPartial().getRGB(255, 255));
        }
    }
    @Test void warmSummaryReopenAndAllZoomsAvoidBodyDecoding() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "example:unknown", -1, 1, 0, 1, 32);
        var cold = new MapTileGenerator(); var first = generate(cold, 1);
        assertEquals(3, cold.decodeCount());
        var warm = new MapTileGenerator();
        assertArrayEquals(pixels(first.image()), pixels(generate(warm, 1).image()));
        for (int bpp : new int[]{2, 4, 8, 16}) assertEquals(3, generate(warm, bpp).sampledChunks());
        assertEquals(0, warm.decodeCount());
        try (var files = Files.list(MapCacheStorage.root(world).resolve("summaries"))) {
            assertEquals(1, files.count()); // 3 slots share a single fixed page.
        }
    }
    @Test void invalidatesMemoryDiskAndParentsButRetainsIndependentRegion() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        MapSamplingFixtures.region(region(2, 0), "minecraft:stone", 0, 1, 0);
        var g = new MapTileGenerator(); var service = new MapTileService(new MapTileMemoryCache(), new MapTileCache(), g);
        var first = service.load(world, dimension, key(1, 0, 0), MapTileGenerationMonitor.NONE);
        service.load(world, dimension, key(1, 4, 0), MapTileGenerationMonitor.NONE);
        var time = Files.getLastModifiedTime(region(0, 0));
        MapSamplingFixtures.region(region(0, 0), "minecraft:dirt", 0, 2, 0);
        Files.setLastModifiedTime(region(0, 0), time);
        var changed = service.load(world, dimension, key(1, 0, 0), MapTileGenerationMonitor.NONE);
        assertNotEquals(first.image().getRGB(0, 0), changed.image().getRGB(0, 0));
        assertEquals(3, g.decodeCount());
        service.load(world, dimension, key(1, 4, 0), MapTileGenerationMonitor.NONE);
        service.load(world, dimension, key(2, 0, 0), MapTileGenerationMonitor.NONE);
        assertEquals(3, g.decodeCount());
        var fresh = new MapTileService();
        assertEquals(changed.image().getRGB(0, 0), fresh.load(world, dimension, key(1, 0, 0), MapTileGenerationMonitor.NONE).image().getRGB(0, 0));
    }
    @Test void sameSignatureBodyChangeRequiresExplicitRefreshAndPersistsNewGeneration() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        var service = new MapTileService(); var k = key(1, 0, 0);
        int before = service.load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0);
        var stamp = Files.getLastModifiedTime(region(0, 0));
        MapSamplingFixtures.region(region(0, 0), "minecraft:dirt", 0, 1, 0);
        Files.setLastModifiedTime(region(0, 0), stamp);
        assertEquals(before, service.load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0));
        service.forceReload(world);
        int after = service.load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0);
        assertNotEquals(before, after);
        assertEquals(after, new MapTileService().load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0));
        try (var files = Files.list(MapCacheStorage.root(world).resolve("summaries"))) { assertEquals(1, files.count()); }
    }
    @Test void haloUsesNeighbourHeightAndTracksItsCreationAndRemoval() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 1, 1, 0);
        var service = new MapTileService(); var k = key(1, 0, 0);
        int isolated = service.load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0);
        MapSamplingFixtures.region(region(-1, 0), "minecraft:stone", 0, 1, 31);
        int bordered = service.load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0);
        var reference = new MapTileRenderer();
        var bounds = new MapTileBounds(-128, 0, 128, 256);
        reference.accept(bounds, MapZoomLevel.BLOCKS_1, -1, 0, BlockColorPalette.resolve("minecraft:stone").rgb(), 15);
        reference.accept(bounds, MapZoomLevel.BLOCKS_1, 0, 0, BlockColorPalette.resolve("minecraft:stone").rgb(), 31);
        assertEquals(reference.render().getRGB(128, 0), bordered);
        assertNotEquals(isolated, bordered);
        Files.delete(region(-1, 0));
        assertEquals(isolated, service.load(world, dimension, k, MapTileGenerationMonitor.NONE).image().getRGB(0, 0));
    }
    @Test void corruptPagesRebuildAndWrongImageMetadataPairIsRejected() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        var service = new MapTileService(); var result = service.load(world, dimension, key(1, 0, 0), MapTileGenerationMonitor.NONE);
        try (var files = Files.list(MapCacheStorage.root(world).resolve("summaries"))) {
            for (Path p : files.toList()) Files.writeString(p, "bad gzip");
        }
        var g = new MapTileGenerator(); generate(g, 2); assertEquals(1, g.decodeCount());
        var altered = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(altered, "png", result.imagePath().toFile());
        assertTrue(new MapTileCache().findReusable(world, dimension, key(1, 0, 0)).isEmpty());
    }
    @Test void clearDuringPartialCannotPublishOldImageOrRefillSummaries() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0, 1);
        var g = new MapTileGenerator(); var service = new MapTileService(new MapTileMemoryCache(), new MapTileCache(), g);
        assertThrows(CancellationException.class, () -> service.load(world, dimension, key(1, 0, 0), new MapTileGenerationMonitor() {
            @Override public boolean isCancelled() { return false; }
            @Override public void onProgress(int done, int total) { }
            @Override public void onPartial(MapTilePartialResult partial) { service.clearMemory(); }
        }));
        assertEquals(0, g.summaryStatistics().entries());
        assertTrue(new MapTileCache().findReusable(world, dimension, key(1, 0, 0)).isEmpty());
    }
    @Test void persistentBudgetDeclinesNewFilesWithoutDeletingExistingData() throws Exception {
        Path file = MapCacheStorage.root(world).resolve("quota-test");
        synchronized (MapCacheStorage.LOCK) {
            assertTrue(MapCacheStorage.write(world, Map.of(file, new byte[8]), 10, 10));
            assertFalse(MapCacheStorage.write(world, Map.of(file, new byte[11]), 10, 10));
            assertEquals(8, Files.size(file));
            assertTrue(MapCacheStorage.write(world, Map.of(file, new byte[6]), 10, 10));
        }
    }
    @Test void concurrentPageCommitsMergeCompletedSlotsAndRetainUnknownIdentity() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0, 1);
        var source = com.mcworldexplorer.region.RegionHeaderIndex.read(region(0, 0)).signature();
        var k0 = new SurfaceSummaryCache.Key(world.getFolderPath().toRealPath(), dimension.id(), 0, 0,
                PreviewLayer.surfaceOverview(), source, "p3-page");
        var k1 = new SurfaceSummaryCache.Key(k0.world(), k0.dimension(), 1, 0, k0.layer(), source, k0.rulesVersion());
        var first = new SurfaceSummaryStore(world, () -> true);
        var second = new SurfaceSummaryStore(world, () -> true);
        first.put(k0, MapSamplingFixtures.summary("minecraft:stone", 0));
        second.put(k1, MapSamplingFixtures.summary("example:unmapped", -1));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { first.close(); return true; });
            var b = pool.submit(() -> { second.close(); return true; });
            assertTrue(a.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(b.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        try (var restored = new SurfaceSummaryStore(world, () -> true)) {
            assertEquals("minecraft:stone", restored.find(k0).blockId(0));
            assertEquals("example:unmapped", restored.find(k1).blockId(0));
            assertEquals("UNKNOWN_2D_COLOR", restored.find(k1).colorDiagnostic(0));
        }
    }
    @Test void changedSourceBetweenGenerationAndCommitCannotBeRelabelledAsFresh() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        var k = key(1, 0, 0); var expected = MapTileCache.snapshot(world, dimension, k);
        var generated = new MapTileGenerator().generate(world, dimension, k, MapTileGenerationMonitor.NONE);
        MapSamplingFixtures.region(region(0, 0), "minecraft:dirt", 0, 2, 0);
        assertThrows(IOException.class, () -> new MapTileCache().store(world, dimension, k, generated, expected));
        assertTrue(new MapTileCache().findReusable(world, dimension, k).isEmpty());
    }
    @Test void damagedNeighbourCannotBeReportedAsACompleteShadedImage() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        MapSamplingFixtures.region(region(-1, 0), "minecraft:stone", 0, 1, 31);
        byte[] data = Files.readAllBytes(region(-1, 0)); data[8196] = 99; Files.write(region(-1, 0), data);
        var partial = generate(new MapTileGenerator(), 1);
        assertFalse(partial.unreadable().isEmpty());
        assertTrue(partial.unreadable().containsPixel(0, 0));
        assertThrows(MapTileIncompleteException.class, () -> new MapTileService().load(world, dimension,
                key(1, 0, 0), MapTileGenerationMonitor.NONE));
    }

    @Test void boundedPageLookupKeepsLayersRulesAndSourceIdentitySeparate() throws Exception {
        MapSamplingFixtures.region(region(-1, -1), "minecraft:stone", 0, 1, 1023);
        var source = com.mcworldexplorer.region.RegionHeaderIndex.read(region(-1, -1)).signature();
        var surface = new SurfaceSummaryCache.Key(world.getFolderPath().toRealPath(), dimension.id(), -1, -1,
                PreviewLayer.surfaceOverview(), source, "p5-page");
        var band = new SurfaceSummaryCache.Key(surface.world(), surface.dimension(), -1, -1,
                PreviewLayer.heightBand(48, 63), source, surface.rulesVersion());
        var rules = new SurfaceSummaryCache.Key(surface.world(), surface.dimension(), -1, -1,
                surface.layer(), source, "p5-other-rule");
        try (var store = new SurfaceSummaryStore(world, () -> true)) {
            store.put(surface, MapSamplingFixtures.summary("minecraft:stone", 0));
            assertNull(store.find(band));
            store.put(band, MapSamplingFixtures.summary("minecraft:dirt", 0));
            store.put(rules, MapSamplingFixtures.summary("example:unknown", 0)); // evicts one of two pages
            assertEquals("minecraft:stone", store.find(surface).blockId(0));
            assertEquals("minecraft:dirt", store.find(band).blockId(0));
        }
        MapSamplingFixtures.region(region(-1, -1), "minecraft:dirt", 0, 2, 1023);
        var changed = new SurfaceSummaryCache.Key(surface.world(), surface.dimension(), -1, -1,
                surface.layer(), com.mcworldexplorer.region.RegionHeaderIndex.read(region(-1, -1)).signature(),
                surface.rulesVersion());
        try (var store = new SurfaceSummaryStore(world, () -> true)) { assertNull(store.find(changed)); }
    }

    @Test void persistedMarkersExportAndCacheClearPreserveWorldAndThreeDimensionalInput() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:nether_portal", 0, 1, 0);
        byte[] original = Files.readAllBytes(region(0, 0));
        var service = new MapTileService(); var tile = key(1, 0, 0);
        var cold = service.load(world, dimension, tile, MapTileGenerationMonitor.NONE);
        var warm = new MapTileService().load(world, dimension, tile, MapTileGenerationMonitor.NONE);
        assertFalse(cold.markers().isEmpty());
        assertEquals(cold.markers(), warm.markers());
        Path exported = new ViewportExporter().exportToDefault(warm.image(), "P5", world.getFolderPath());
        assertArrayEquals(pixels(cold.image()), pixels(javax.imageio.ImageIO.read(exported.toFile())));
        service.forceReload(world);
        assertFalse(new WorldMapCacheCleaner().clear(world).hasFailures());
        service.clearMemory();
        assertFalse(Files.exists(MapCacheStorage.root(world)));
        var refreshed = service.load(world, dimension, tile, MapTileGenerationMonitor.NONE);
        assertEquals(cold.markers(), refreshed.markers());
        assertArrayEquals(pixels(cold.image()), pixels(refreshed.image()));
        var area = new com.mcworldexplorer.voxel.io.VoxelAreaLoader().load(
                new com.mcworldexplorer.voxel.io.VoxelAreaRequest(world.getFolderPath(), dimension.id(),
                        com.mcworldexplorer.voxel.data.ChunkRectangle.target(0, 0, 0, 0)),
                com.mcworldexplorer.voxel.io.CancellationSignal.NONE);
        var scene = new com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder().build(
                area, com.mcworldexplorer.voxel.io.CancellationSignal.NONE);
        assertFalse(scene.cancelled());
        assertTrue(scene.snapshot().isPresent());
        assertTrue(scene.snapshot().orElseThrow().faceCount() > 0);
        assertArrayEquals(original, Files.readAllBytes(region(0, 0)));
        assertTrue(Files.isRegularFile(exported), "Clearing cache must not remove an exported map");
    }

    @Test void nearSourceLookupDetectsCreationDeletionAndDamage() throws Exception {
        var k = key(1, 0, 0);
        assertTrue(MapTileCache.snapshot(world, dimension, k).sources().stream().noneMatch(MapTileSourceState::exists));
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        var snapshot = MapTileCache.snapshot(world, dimension, k);
        assertTrue(snapshot.sources().stream().anyMatch(s0 -> s0.regionX() == 0 && s0.regionZ() == 0 && s0.exists()));
        Files.delete(region(0, 0));
        assertTrue(MapTileCache.snapshot(world, dimension, k).sources().stream().noneMatch(MapTileSourceState::exists));
        Files.write(region(0, 0), new byte[99]);
        assertTrue(MapTileCache.snapshot(world, dimension, k).sources().stream().anyMatch(MapTileSourceState::unreadable));
    }

    @Test void reusedStartSnapshotStillRejectsChangesAndAtomicFailureLeavesNoPendingFile() throws Exception {
        MapSamplingFixtures.region(region(0, 0), "minecraft:stone", 0, 1, 0);
        var k = key(1, 0, 0); new MapTileService().load(world, dimension, k, MapTileGenerationMonitor.NONE);
        var expected = MapTileCache.snapshot(world, dimension, k);
        MapSamplingFixtures.region(region(0, 0), "minecraft:dirt", 0, 2, 0);
        assertTrue(new MapTileCache().findReusable(world, dimension, k, expected).isEmpty());
        Path target = temp.resolve("atomic-output");
        MapCacheStorage.atomic(target, new byte[]{1});
        MapCacheStorage.atomic(target, new byte[]{2, 3});
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(target));
        Path blocked = Files.createDirectory(temp.resolve("occupied-directory"));
        Files.writeString(blocked.resolve("keep"), "original");
        assertThrows(IOException.class, () -> MapCacheStorage.atomic(blocked, new byte[]{4}));
        assertEquals("original", Files.readString(blocked.resolve("keep")));
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(f -> f.getFileName().toString().startsWith(".pending-")));
        }
    }

    static int[] pixels(java.awt.image.BufferedImage image) { return image.getRGB(0, 0, 256, 256, null, 0, 256); }
}
