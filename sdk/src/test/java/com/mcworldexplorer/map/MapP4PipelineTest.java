package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class MapP4PipelineTest {
    static { org.slf4j.LoggerFactory.getLogger(MapP4PipelineTest.class); }
    @TempDir Path temp;
    private static MapTileKey key(int x) {
        return new MapTileKey("world", "minecraft:overworld", PreviewLayer.surfaceOverview(), MapZoomLevel.BLOCKS_1, x, 0, "p4");
    }
    private static MapDisplayQueue.Frame frame() { return new MapDisplayQueue.Frame(new int[256 * 256], List.of()); }
    @Test void coalescesPartialFramesAndNeverReplacesACompleteResultWithPartial() {
        try (var queue = new MapDisplayQueue()) {
            queue.retain(1, Set.of(key(0))); AtomicInteger applied = new AtomicInteger();
            for (int i = 0; i < 100; i++) assertTrue(queue.offer(1, key(0), frame(), false, ignored -> applied.incrementAndGet()));
            assertEquals(1, queue.size());
            assertTrue(queue.offer(1, key(0), frame(), true, ignored -> applied.addAndGet(10)));
            assertFalse(queue.offer(1, key(0), frame(), false, ignored -> fail()));
            queue.poll().apply(); assertEquals(10, applied.get()); assertEquals(0, queue.bytes());
        }
    }
    @Test void boundedFinalDeliveryWaitsAndContextChangeReleasesBlockedProducer() throws Exception {
        long one = frame().bytes();
        try (var queue = new MapDisplayQueue(one); var pool = Executors.newSingleThreadExecutor()) {
            queue.retain(1, Set.of(key(0), key(1)));
            assertTrue(queue.offer(1, key(0), frame(), true, ignored -> {}));
            assertFalse(queue.offer(1, key(1), frame(), false, ignored -> {}));
            var entered = new CountDownLatch(1);
            var blocked = pool.submit(() -> { entered.countDown(); return queue.offer(1, key(1), frame(), true, ignored -> fail()); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> blocked.get(80, TimeUnit.MILLISECONDS));
            queue.retain(2, Set.of(key(1)));
            assertFalse(blocked.get(2, TimeUnit.SECONDS)); assertEquals(0, queue.size());
            assertTrue(queue.peakBytes() <= one);
            assertFalse(queue.offer(1, key(1), frame(), true, ignored -> fail()));
        }
    }
    @Test void consumingFreesCapacityAndPixelPreparationDoesNotRetainTheImage() throws Exception {
        var image = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff123456); var prepared = MapDisplayQueue.Frame.prepare(image, List.of());
        image.setRGB(0, 0, 0); assertEquals(0xff123456, prepared.pixels()[0]);
        try (var queue = new MapDisplayQueue(prepared.bytes()); var pool = Executors.newSingleThreadExecutor()) {
            queue.retain(1, Set.of(key(0), key(1))); queue.offer(1, key(0), prepared, true, ignored -> {});
            var blocked = pool.submit(() -> queue.offer(1, key(1), frame(), true, ignored -> {}));
            queue.poll(); assertTrue(blocked.get(2, TimeUnit.SECONDS)); assertEquals(key(1), queue.poll().key());
        }
    }
    @Test void schedulerCapsQueuedTasksAndRejectsStaleAndClosedRequests() throws Exception {
        var start = new CountDownLatch(2); var release = new CountDownLatch(1);
        try (var scheduler = new MapTileScheduler()) {
            long request = scheduler.beginRequest(Set.of());
            for (int i = 0; i < 2; i++) assertTrue(scheduler.submit(key(i), i, request, c -> {
                start.countDown(); release.await(5, TimeUnit.SECONDS); return null;
            }, r -> {}, e -> {}));
            assertTrue(start.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++) assertTrue(scheduler.submit(key(i + 2), i, request, c -> null, r -> {}, e -> {}));
            assertEquals(256, scheduler.queuedCount());
            assertFalse(scheduler.submit(key(999), 0, request, c -> null, r -> {}, e -> {}));
            scheduler.beginRequest(Set.of()); assertEquals(0, scheduler.queuedCount());
            assertFalse(scheduler.submit(key(999), 0, request, c -> null, r -> {}, e -> {}));
            release.countDown(); scheduler.close();
            assertFalse(scheduler.submitIndex(c -> fail(), e -> fail()));
        } finally { release.countDown(); }
    }
    @Test void visibleWorkPrecedesOverviewAndOverviewPrecedesPrefetch() throws Exception {
        var entered = new CountDownLatch(2); var first = new CountDownLatch(1); var second = new CountDownLatch(1);
        var done = new CountDownLatch(3); var order = new CopyOnWriteArrayList<String>();
        try (var scheduler = new MapTileScheduler()) {
            long request = scheduler.beginRequest(Set.of());
            scheduler.submit(key(0), 0, request, c -> { entered.countDown(); first.await(5, TimeUnit.SECONDS); return null; }, r -> {}, e -> {});
            scheduler.submit(key(1), 0, request, c -> { entered.countDown(); second.await(5, TimeUnit.SECONDS); return null; }, r -> {}, e -> {});
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            scheduler.submit(key(2), 1_000_000, request, c -> { order.add("prefetch"); done.countDown(); return null; }, r -> {}, e -> fail());
            scheduler.submitIndex(c -> { order.add("index"); done.countDown(); }, e -> fail());
            scheduler.submit(key(3), 0, request, c -> { order.add("visible"); done.countDown(); return null; }, r -> {}, e -> fail());
            first.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("visible", "index", "prefetch"), order);
        } finally { first.countDown(); second.countDown(); }
    }

    @Test void overviewSharesWorkersAndCanBeCancelledWithoutCancellingVisibleTile() throws Exception {
        var entered = new CountDownLatch(1); var finished = new CountDownLatch(1); var tileDone = new CountDownLatch(1);
        try (var scheduler = new MapTileScheduler()) {
            long request = scheduler.beginRequest(Set.of(key(0)));
            assertTrue(scheduler.submitIndex(c -> { entered.countDown(); while (!c.isCancelled()) Thread.sleep(5); finished.countDown(); }, e -> fail()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            scheduler.submit(key(0), 0, request, c -> null, r -> tileDone.countDown(), e -> fail());
            scheduler.cancelIndex();
            assertTrue(finished.await(2, TimeUnit.SECONDS)); assertTrue(tileDone.await(2, TimeUnit.SECONDS));
        }
    }
    @Test void indexesSparseNegativeAndFarLocationsWithoutDecodingInvalidBody() throws Exception {
        MapSamplingFixtures.region(temp.resolve("r.-2.1.mca"), "minecraft:stone", 0, 1, 0, 1023);
        byte[] damaged = Files.readAllBytes(temp.resolve("r.-2.1.mca")); damaged[8196] = 99;
        Files.write(temp.resolve("r.-2.1.mca"), damaged);
        MapSamplingFixtures.region(temp.resolve("r.100000.-100000.mca"), "minecraft:stone", 0, 1, 17);
        var snapshots = new ArrayList<SavedRangeIndex.Snapshot>();
        SavedRangeIndex.scan(temp, () -> false, snapshots::add);
        var result = snapshots.getLast(); assertTrue(result.complete()); assertEquals(3, result.chunks());
        assertEquals(0, result.failures()); assertEquals(0, result.invalidChunks());
        assertEquals(2, result.regions().size());
        var bounds = result.bounds(); assertEquals(-1024, bounds.minX()); assertEquals(-51200000, bounds.minZ());
        assertEquals(-1234.5, bounds.worldX(bounds.screenX(-1234.5)), .00001);
        assertEquals(-987.5, bounds.worldZ(bounds.screenZ(-987.5)), .00001);
    }
    @Test void preservesHeaderFailuresAndInvalidPositionsAndReportsBudgetTruncation() throws Exception {
        byte[] bytes = new byte[8192]; ByteBuffer.wrap(bytes).putInt(257); // points into the header
        Files.write(temp.resolve("r.0.0.mca"), bytes);
        Files.write(temp.resolve("r.1.0.mca"), new byte[7]);
        Files.write(temp.resolve("r.2.0.mca"), new byte[8192]);
        var results = new ArrayList<SavedRangeIndex.Snapshot>(); SavedRangeIndex.scan(temp, () -> false, results::add);
        var result = results.getLast(); assertEquals(1, result.invalidChunks()); assertEquals(1, result.failures());
        assertTrue(result.regions().stream().anyMatch(r -> !r.failure().isEmpty()));
        assertTrue(Arrays.stream(result.pixels()).anyMatch(color -> color == 0xffd45c58));
        results.clear(); SavedRangeIndex.scan(temp, () -> false, results::add, 2);
        assertTrue(results.getLast().limited()); assertFalse(results.getLast().complete()); assertEquals(2, results.getLast().regions().size());
    }
    @Test void cancellationAndEmptyWorldAreNotReportedAsSuccessfulTerrain() throws Exception {
        var results = new ArrayList<SavedRangeIndex.Snapshot>(); SavedRangeIndex.scan(temp, () -> false, results::add);
        assertEquals(0, results.getLast().chunks()); assertNull(results.getLast().bounds());
        MapSamplingFixtures.region(temp.resolve("r.0.0.mca"), "minecraft:stone", 0, 1, 0);
        assertThrows(CancellationException.class, () -> SavedRangeIndex.scan(temp, () -> true, value -> fail()));
        Files.delete(temp.resolve("r.0.0.mca")); // cancelled scan released its directory/file resources
    }
    @Test void clearingMemoryDoesNotWaitForPersistentRefreshIo() throws Exception {
        String previous = System.getProperty("mcworldexplorer.home");
        System.setProperty("mcworldexplorer.home", temp.resolve("app").toString());
        var world = new com.mcworldexplorer.world.WorldInfo(temp.resolve("world"));
        var service = new MapTileService(); var thread = new AtomicReference<Thread>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> refresh;
            synchronized (MapCacheStorage.LOCK) {
                refresh = pool.submit(() -> { thread.set(Thread.currentThread()); service.forceReload(world); return null; });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((thread.get() == null || thread.get().getState() != Thread.State.BLOCKED) && System.nanoTime() < deadline) Thread.sleep(5);
                assertNotNull(thread.get()); assertEquals(Thread.State.BLOCKED, thread.get().getState());
                pool.submit(service::clearMemory).get(500, TimeUnit.MILLISECONDS);
            }
            refresh.get(2, TimeUnit.SECONDS);
        } finally {
            if (previous == null) System.clearProperty("mcworldexplorer.home"); else System.setProperty("mcworldexplorer.home", previous);
        }
    }

    @Test void cpuImageBudgetIncludesSourceMetadataAndNotOnlyEntryCount() {
        var cache = new MapTileMemoryCache(1000);
        var sources = new ArrayList<MapTileSourceState>();
        for (int i = 0; i < 80; i++) sources.add(new MapTileSourceState(i, 0, true, "source"));
        for (int i = 0; i < 100; i++) cache.put(key(i), new MapTileCacheResult(Path.of("image"), Path.of("metadata"),
                new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB), List.of(), sources, "world"));
        assertTrue(cache.estimatedBytes() <= MapTileMemoryCache.MAX_BYTES);
        assertTrue(cache.size() < 64); assertTrue(cache.get(key(99)).isPresent()); assertTrue(cache.get(key(0)).isEmpty());
    }
    @Test void storageWarningIsScopedToItsWorld() {
        var a = new com.mcworldexplorer.world.WorldInfo(temp.resolve("a"));
        var b = new com.mcworldexplorer.world.WorldInfo(temp.resolve("b"));
        try { MapCacheStorageNotice.limited(a); assertFalse(MapCacheStorageNotice.message(a).isEmpty());
            assertTrue(MapCacheStorageNotice.message(b).isEmpty()); }
        finally { MapCacheStorageNotice.clear(); }
    }

    @Test void loadTrackerDiscardsStatesOutsideCurrentViewport() {
        var tracker = new MapTileLoadTracker(); tracker.ensureAutomaticLoading(key(0)); tracker.ensureAutomaticLoading(key(1));
        tracker.fail(key(1)); tracker.retain(Set.of(key(0))); assertFalse(tracker.isFailed(key(1))); assertTrue(tracker.isLoading(key(0)));
    }
}
