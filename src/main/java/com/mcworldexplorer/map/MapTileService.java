package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CancellationException;

public final class MapTileService {
    private final MapTileMemoryCache memoryCache;
    private final MapTileCache diskCache;
    private final MapTileGenerator generator;
    private long epoch;
    private int activeLoads;
    private boolean closing;
    private boolean exitTimedOut;
    public enum ExitState { STOPPED, TIMED_OUT, INTERRUPTED }
    public synchronized int activeLoads() { return activeLoads; }
    /** Cancel in-flight work and wait without holding a monitor needed by workers. Timeout is not success. */
    public ExitState closeAndAwait(long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative shutdown timeout");
        long remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long previous = System.nanoTime();
        synchronized (this) {
            closing = true; epoch++;
            while (activeLoads != 0) {
                if (remaining <= 0) { exitTimedOut = true; return ExitState.TIMED_OUT; }
                try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return ExitState.INTERRUPTED; }
                long now = System.nanoTime(); remaining -= now - previous; previous = now;
            }
            return exitTimedOut ? ExitState.TIMED_OUT : ExitState.STOPPED;
        }
    }

    public MapTileService() {
        this(new MapTileMemoryCache(), new MapTileCache(), new MapTileGenerator());
    }

    MapTileService(
            MapTileMemoryCache memoryCache,
            MapTileCache diskCache,
            MapTileGenerator generator) {
        this.memoryCache = memoryCache;
        this.diskCache = diskCache;
        this.generator = generator;
    }

    public MapTileCacheResult load(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            MapTileGenerationMonitor monitor) throws IOException {
        return load(world, dimension, key, monitor, false);
    }

    /** 命中完整缓存立即返回；否则只交付中心部分图，null表示仍需完整生成。 */
    public MapTileCacheResult previewCenter(WorldInfo world, WorldDimension dimension, MapTileKey key,
                                          MapTileGenerationMonitor monitor) throws IOException {
        if (!monitor.centerFirst() || !MapTileGenerator.intersectsCenter(key, monitor.focusX(), monitor.focusZ()))
            throw new IllegalArgumentException("Center preview needs a visible intersection");
        return load(world, dimension, key, monitor, true);
    }

    private MapTileCacheResult load(WorldInfo world, WorldDimension dimension, MapTileKey key,
                                   MapTileGenerationMonitor monitor, boolean centerOnly) throws IOException {
        final long admissionEpoch;
        synchronized (this) { if (closing) throw new CancellationException("Map service closed"); admissionEpoch = epoch; activeLoads++; }
        MapPersistence.begin(() -> monitor.isCancelled() || !isCurrent(admissionEpoch));
        try {
            return loadInternal(world, dimension, key, monitor, centerOnly, admissionEpoch);
        }
        catch (CancellationException superseded) {
            // Display supersession alone does not invalidate admitted summaries.
            if (monitor.isObsolete() && !monitor.isCancelled() && isCurrent(admissionEpoch)) MapPersistence.await();
            throw superseded;
        }
        finally { MapPersistence.end(); synchronized (this) { activeLoads--; notifyAll(); } }
    }

    private MapTileCacheResult loadInternal(WorldInfo world, WorldDimension dimension, MapTileKey key,
                                             MapTileGenerationMonitor monitor, boolean centerOnly, long requestEpoch) throws IOException {
        check(requestEpoch, monitor);
        var expected = MapTileCache.snapshot(world, dimension, key);
        boolean readable = expected.sources().stream().noneMatch(MapTileSourceState::unreadable);
        Optional<MapTileCacheResult> inMemory = readable ? memoryCache.get(key) : Optional.empty();
        if (inMemory.isPresent()) {
            var cached = inMemory.orElseThrow();
            if (expected.identity().equals(cached.identity()) && expected.sources().equals(cached.sources())) {
                synchronized (this) { check(requestEpoch, monitor); return cached; }
            }
            memoryCache.remove(key);
        }
        Optional<MapTileCacheResult> onDisk = readable ? diskCache.findReusable(world, dimension, key, expected) : Optional.empty();
        if (onDisk.isPresent()) {
            var result = onDisk.orElseThrow();
            synchronized (this) {
                check(requestEpoch, monitor);
                memoryCache.put(key, result); return result;
            }
        }
        if (MapTileCache.contextBytes(expected, key) > (512L << 10))
            throw new IOException("Map producer source context budget exceeded");
        try (var producer = MapPersistence.producer()) {
        MapTileGenerationResult generated = generator.generate(world, dimension, key, monitor, true, centerOnly);
        check(requestEpoch, monitor);
        if (monitor.isObsolete()) throw new CancellationException("Display request superseded; requested terrain retained");
        if (centerOnly) {
            if (!expected.equals(MapTileCache.snapshot(world, dimension, key)))
                throw new IOException("Source changed during center preview");
            monitor.onPartial(new MapTilePartialResult(generated.image(), generated.markers(),
                    generated.sampledChunks() + generated.missingChunks(), generated.totalChunks()));
            MapPersistence.await(); // 中心预览也要等待已接纳摘要的保存终态。
            return null; // 不存为完整PNG，不标记成功；有效摘要使用现有内存与持久化缓存。
        }
        if (generated.failedChunks() > 0 || !generated.unreadable().isEmpty()) {
            if (!expected.equals(MapTileCache.snapshot(world, dimension, key)))
                throw new IOException("Source changed during map sampling");
            throw new MapTileIncompleteException(generated);
        }
        var saved = new java.util.concurrent.atomic.AtomicReference<MapTileCacheResult>();
        var cache = diskCache;
        long owned = MapTileCache.retainedBytes(generated.image(), generated.markers(), expected, key);
        try {
            MapPersistence.submit(owned, () -> saved.set(cache.store(world, dimension, key, generated, expected)));
        } catch (MapPersistence.LimitExceeded limit) {
            MapCacheStorageNotice.unavailable(world);
            MapPersistence.failed(MapTileCacheResult.SaveState.ENCODING_LIMIT);
            saved.set(new MapTileCacheResult(null, null, generated.image(), generated.markers(), expected.sources(), expected.identity(), MapTileCacheResult.SaveState.ENCODING_LIMIT));
        }
        MapPersistence.await();
        var stored = MapPersistence.finish(saved.get());
        synchronized (this) {
            check(requestEpoch, monitor); memoryCache.put(key, stored); return stored;
        }
        }
    }

    private synchronized boolean isCurrent(long value) { return !closing && value == epoch; }

    private synchronized void check(long requestEpoch, MapTileGenerationMonitor monitor) {
        if (closing || requestEpoch != epoch || monitor.isCancelled() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Map request invalidated");
    }

    /** Explicit refresh also invalidates same-signature body changes across process restarts. */
    public void forceReload(WorldInfo world) throws IOException {
        synchronized (this) {
            if (closing) throw new CancellationException("Map service closed");
            activeLoads++;
        }
        try {
            clearMemory();
            synchronized (MapCacheStorage.LOCK) {
                synchronized (this) {
                    if (closing || Thread.currentThread().isInterrupted())
                        throw new CancellationException("Refresh invalidated");
                }
                MapCacheStorage.force(world);
            }
        } finally {
            clearMemory();
            synchronized (this) { activeLoads--; notifyAll(); }
        }
    }

    public synchronized void clearMemory() {
        epoch++;
        memoryCache.clear();
        generator.clearMemory();
    }
}
