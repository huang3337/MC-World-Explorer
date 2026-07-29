package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTileSchedulerTest {
    @Test
    void limitsConcurrencyToTwoWorkers() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);
        try (MapTileScheduler scheduler = new MapTileScheduler()) {
            Set<MapTileKey> keys = Set.of(key(0), key(1), key(2), key(3));
            long request = scheduler.beginRequest(keys);
            for (MapTileKey key : keys) {
                scheduler.submit(key, key.tileX(), request, cancellation -> {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    release.await(2, TimeUnit.SECONDS);
                    active.decrementAndGet();
                    return value(key);
                }, ignored -> finished.countDown(), ignored -> finished.countDown());
            }

            assertTrue(waitUntil(() -> maximum.get() == 2, Duration.ofSeconds(2)));
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
        }
    }

    @Test
    void dropsResultsFromTilesRemovedByNewRequest() throws Exception {
        List<Long> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MapTileKey stale = key(0);
        try (MapTileScheduler scheduler = new MapTileScheduler()) {
            long first = scheduler.beginRequest(Set.of(stale));
            scheduler.submit(stale, 0, first, cancellation -> {
                started.countDown();
                release.await(2, TimeUnit.SECONDS);
                return value(stale);
            }, result -> delivered.add(stale.tileX()), ignored -> {
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            scheduler.beginRequest(Set.of());
            release.countDown();
            Thread.sleep(100);

            assertFalse(delivered.contains(stale.tileX()));
        }
    }

    @Test
    void rebindsRetainedTileToLatestRequestCallback() throws Exception {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        MapTileKey retained = key(0);
        try (MapTileScheduler scheduler = new MapTileScheduler()) {
            long first = scheduler.beginRequest(Set.of(retained));
            scheduler.submit(retained, 10, first, cancellation -> {
                started.countDown();
                release.await(2, TimeUnit.SECONDS);
                return value(retained);
            }, result -> delivered.add("first"), ignored -> {
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            long second = scheduler.beginRequest(Set.of(retained));
            scheduler.submit(retained, 0, second, cancellation -> value(retained), result -> {
                delivered.add("second");
                finished.countDown();
            }, ignored -> finished.countDown());
            release.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("second"), delivered);
        }
    }

    private static boolean waitUntil(Check check, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.test()) {
                return true;
            }
            Thread.sleep(10);
        }
        return check.test();
    }

    private static MapTileKey key(long tileX) {
        return new MapTileKey(
                "world",
                "minecraft:overworld",
                PreviewLayer.surfaceOverview(),
                MapZoomLevel.BLOCKS_1,
                tileX,
                0,
                "v1");
    }

    private static MapTileCacheResult value(MapTileKey key) {
        return new MapTileCacheResult(
                Path.of(key.tileX() + ".png"),
                Path.of(key.tileX() + ".json"),
                new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB),
                List.of());
    }

    @FunctionalInterface
    private interface Check {
        boolean test();
    }
}
