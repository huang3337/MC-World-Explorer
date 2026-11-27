package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.region.RegionHeaderIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SurfaceSummaryCacheTest {
    private static SurfaceSummaryCache.Key key(int x) {
        return new SurfaceSummaryCache.Key(Path.of("world"), "minecraft:overworld", x, 0,
                PreviewLayer.surfaceOverview(), new RegionHeaderIndex.Signature(Path.of("region"),
                8192, FileTime.fromMillis(0), "id", "hash"), "test");
    }

    @Test void mergesConcurrentRequestsWhileOwnerCancellationDoesNotPoisonWaiter() throws Exception {
        var cache = new SurfaceSummaryCache();
        var summary = MapSamplingFixtures.summary("minecraft:stone", 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancel = new AtomicBoolean();
        var loads = new AtomicInteger();
        var monitor = new MapTileGenerationMonitor() { public boolean isCancelled() { return cancel.get(); } };
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ChunkSurfaceSummary> owner = pool.submit(() -> cache.get(key(0), 0, monitor, () -> {
                loads.incrementAndGet(); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return summary;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var waiter = pool.submit(() -> cache.get(key(0), 0, MapTileGenerationMonitor.NONE, () -> {
                    loads.incrementAndGet(); return summary;
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (cache.statistics().joins() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(1, cache.statistics().joins());
                cancel.set(true); release.countDown();
                assertSame(summary, waiter.get(5, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class,
                        assertThrows(ExecutionException.class, () -> owner.get(5, TimeUnit.SECONDS)).getCause());
                assertEquals(1, loads.get());
                assertEquals(0, cache.statistics().inFlight());
            } finally { release.countDown(); }
        }
    }

    @Test void failuresAreRetryableAndBudgetEvictsLeastRecentlyUsed() throws Exception {
        var value = MapSamplingFixtures.summary("example:unmapped", -1);
        assertEquals("example:unmapped", value.blockId(0));
        assertEquals("UNKNOWN_2D_COLOR", value.colorDiagnostic(0));
        var cache = new SurfaceSummaryCache(value.estimatedBytes());
        assertThrows(IOException.class, () -> cache.get(key(0), 0, MapTileGenerationMonitor.NONE,
                () -> { throw new IOException("broken chunk"); }));
        assertEquals(0, cache.statistics().inFlight());
        cache.get(key(0), 0, MapTileGenerationMonitor.NONE, () -> value);
        cache.get(key(1), 0, MapTileGenerationMonitor.NONE, () -> value);
        assertEquals(1, cache.statistics().entries());
        assertTrue(cache.statistics().bytes() <= value.estimatedBytes());
        long loads = cache.statistics().loads();
        cache.get(key(0), 0, MapTileGenerationMonitor.NONE, () -> value);
        assertEquals(loads + 1, cache.statistics().loads());
    }

    @Test void clearDuringLoadPreventsRepopulationAndOldDelivery() throws Exception {
        var cache = new SurfaceSummaryCache();
        var value = MapSamplingFixtures.summary("minecraft:stone", 0);
        assertThrows(CancellationException.class, () -> cache.get(key(0), 0, MapTileGenerationMonitor.NONE, () -> {
            cache.clear(); return value;
        }));
        assertEquals(0, cache.statistics().bytes());
        assertEquals(0, cache.statistics().entries());
        assertEquals(0, cache.statistics().inFlight());
        assertSame(value, cache.get(key(0), cache.epoch(), MapTileGenerationMonitor.NONE, () -> value));
    }

    @Test void zeroBudgetStillReturnsResultWithoutRetention() throws Exception {
        var cache = new SurfaceSummaryCache(0);
        var value = MapSamplingFixtures.summary("minecraft:stone", 0);
        assertSame(value, cache.get(key(0), 0, MapTileGenerationMonitor.NONE, () -> value));
        assertEquals(0, cache.statistics().entries());
        assertEquals(0, cache.statistics().bytes());
    }

    @Test void cancellingWaiterLeavesOwnerAndCachedResultUsable() throws Exception {
        var cache = new SurfaceSummaryCache();
        var value = MapSamplingFixtures.summary("minecraft:stone", 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancel = new AtomicBoolean();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> cache.get(key(0), 0, MapTileGenerationMonitor.NONE, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return value;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var waiter = pool.submit(() -> cache.get(key(0), 0,
                        new MapTileGenerationMonitor() { public boolean isCancelled() { return cancel.get(); } },
                        () -> { throw new AssertionError("Duplicate loader"); }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (cache.statistics().joins() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(1, cache.statistics().joins());
                cancel.set(true);
                assertInstanceOf(CancellationException.class,
                        assertThrows(ExecutionException.class, () -> waiter.get(5, TimeUnit.SECONDS)).getCause());
                release.countDown();
                assertSame(value, owner.get(5, TimeUnit.SECONDS));
                assertSame(value, cache.get(key(0), 0, MapTileGenerationMonitor.NONE,
                        () -> { throw new AssertionError("Lost shared result"); }));
            } finally { release.countDown(); }
        }
    }
}
