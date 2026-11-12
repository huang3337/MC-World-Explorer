package com.mcworldexplorer.map;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class MapTileScheduler implements AutoCloseable {
    private static final int WORKER_COUNT = 2;
    public static final int MAX_QUEUED = 256;
    private boolean closed;

    private final AtomicLong sequence = new AtomicLong();
    private final ThreadPoolExecutor executor;
    private final Map<MapTileKey, ScheduledTile> pending = new HashMap<>();
    private long requestId;
    private Set<MapTileKey> desired = Set.of();
    private final Set<ScheduledTile> running = new java.util.HashSet<>();

    public MapTileScheduler() {
        AtomicLong workerSequence = new AtomicLong();
        executor = new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "map-tile-worker-" + workerSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public synchronized long beginRequest(Set<MapTileKey> retainedKeys) {
        requestId++;
        desired = Set.copyOf(retainedKeys);
        for (ScheduledTile task : running) {
            if (task.terrainContinuation && !retainedKeys.contains(task.key)) {
                task.obsolete = true;
                if (!needed(task.key, null, null)) task.cancel();
            }
        }
        pending.entrySet().removeIf(entry -> {
            if (entry.getKey() == null || retainedKeys.contains(entry.getKey())) {
                return false;
            }
            ScheduledTile task = entry.getValue();
            if (!task.terrainContinuation || !running.contains(task) || !needed(task.key, null, null)) task.cancel();
            return true;
        });
        return requestId;
    }

    public synchronized boolean submit(
            MapTileKey key,
            long priority,
            long ownerRequestId,
            TileLoader loader,
            Consumer<MapTileCacheResult> onSuccess,
            Consumer<Throwable> onFailure) {
        return submitProgressive(key, priority, ownerRequestId, null, loader, onSuccess, onFailure);
    }

    public synchronized boolean submitProgressive(MapTileKey key, long priority, long ownerRequestId,
            TileLoader preview, TileLoader loader, Consumer<MapTileCacheResult> onSuccess,
            Consumer<Throwable> onFailure) {
        java.util.Objects.requireNonNull(key, "tile key");
        if (closed || ownerRequestId != requestId) return false;
        ScheduledTile existing = pending.get(key);
        if (existing != null) {
            boolean queued = executor.remove(existing);
            existing.rebind(priority, ownerRequestId, onSuccess, onFailure);
            if (queued) {
                executor.execute(existing);
            }
            return true;
        }
        if (executor.getQueue().size() >= MAX_QUEUED) return false;
        ScheduledTile task = new ScheduledTile(
                key,
                priority,
                sequence.getAndIncrement(),
                ownerRequestId,
                loader,
                onSuccess,
                onFailure);
        pending.put(key, task);
        task.preview = preview;
        executor.execute(task);
        return true;
    }

    /** Only terrain-aware loaders may finish requested chunks after their display request expires. */
    public synchronized boolean submitTerrain(MapTileKey key, long priority, long request,
            TileLoader preview, TileLoader loader, Consumer<MapTileCacheResult> success, Consumer<Throwable> failure) {
        boolean accepted = submitProgressive(key, priority, request, preview, loader, success, failure);
        if (accepted) pending.get(key).terrainContinuation = true;
        return accepted;
    }

    private synchronized boolean needed(MapTileKey old, Integer chunkX, Integer chunkZ) {
        if (closed || old == null) return false;
        var bounds = old.bounds();
        long x0 = chunkX == null ? bounds.minX() - 16 : (long) chunkX * 16;
        long z0 = chunkZ == null ? bounds.minZ() - 16 : (long) chunkZ * 16;
        long x1 = chunkX == null ? bounds.maxXExclusive() : x0 + 16;
        long z1 = chunkZ == null ? bounds.maxZExclusive() : z0 + 16;
        for (var key : desired) {
            if (!old.worldId().equals(key.worldId()) || !old.dimensionId().equals(key.dimensionId())
                    || !old.layer().equals(key.layer()) || !old.renderVersion().equals(key.renderVersion())) continue;
            var b = key.bounds();
            if (x0 < b.maxXExclusive() && x1 > b.minX() - 16 && z0 < b.maxZExclusive() && z1 > b.minZ() - 16) return true;
        }
        return false;
    }

    // One header-only overview shares the two existing workers; null is its internal task key.
    public synchronized boolean submitIndex(IndexLoader loader, Consumer<Throwable> onFailure) {
        cancelIndex();
        if (closed || executor.getQueue().size() >= MAX_QUEUED) return false;
        ScheduledTile task = new ScheduledTile(null, 100_000L, sequence.getAndIncrement(), requestId,
                cancellation -> { loader.load(cancellation); return null; }, ignored -> { }, onFailure);
        pending.put(null, task); executor.execute(task); return true;
    }
    public synchronized void cancelIndex() {
        ScheduledTile task = pending.remove(null); if (task != null) task.cancel();
    }
    public synchronized boolean contains(MapTileKey key) { return pending.containsKey(key); }
    public synchronized boolean indexPending() { return pending.containsKey(null); }
    public synchronized int queuedCount() { return executor.getQueue().size(); }
    public synchronized int pendingCount() { return pending.size(); }
    @FunctionalInterface public interface IndexLoader { void load(Cancellation cancellation) throws Exception; }

    public synchronized boolean isCurrent(long candidateRequestId) {
        return requestId == candidateRequestId;
    }

    public synchronized void cancelAll() {
        requestId++;
        desired = Set.of();
        running.forEach(ScheduledTile::cancel);
        pending.values().forEach(ScheduledTile::cancel);
        pending.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelAll();
        executor.shutdownNow();
    }

    private final class ScheduledTile implements Runnable, Comparable<ScheduledTile> {
        private final MapTileKey key;
        private long priority;
        private final long sequenceNumber;
        private long ownerRequestId;
        private final TileLoader loader;
        private TileLoader preview;
        private Consumer<MapTileCacheResult> onSuccess;
        private Consumer<Throwable> onFailure;
        private volatile boolean cancelled;
        private volatile boolean obsolete;
        private boolean terrainContinuation;
        private final Cancellation token = new Cancellation() {
            public boolean isCancelled() { return cancelled; }
            public boolean isObsolete() { return obsolete; }
            public boolean needsChunk(int x, int z) { return !obsolete || needed(key, x, z); }
        };

        private ScheduledTile(
                MapTileKey key,
                long priority,
                long sequenceNumber,
                long ownerRequestId,
                TileLoader loader,
                Consumer<MapTileCacheResult> onSuccess,
                Consumer<Throwable> onFailure) {
            this.key = key;
            this.priority = priority;
            this.sequenceNumber = sequenceNumber;
            this.ownerRequestId = ownerRequestId;
            this.loader = loader;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }

        private void rebind(
                long priority,
                long ownerRequestId,
                Consumer<MapTileCacheResult> onSuccess,
                Consumer<Throwable> onFailure) {
            this.priority = priority;
            this.ownerRequestId = ownerRequestId;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }

        @Override
        public void run() {
            synchronized (MapTileScheduler.this) {
                if (cancelled || closed) return;
                running.add(this);
            }
            boolean handedOff = false;
            try {
                MapTileCacheResult result = null;
                if (preview != null) {
                    result = preview.load(token);
                    synchronized (MapTileScheduler.this) {
                        preview = null;
                        if (cancelled || requestId != ownerRequestId) { pending.remove(key, this); return; }
                        if (result == null && executor.getQueue().size() < MAX_QUEUED) {
                            running.remove(this);
                            handedOff = true;
                            executor.execute(this); // 释放worker，让其他中心小片先生成；不等待屏障。
                            return;
                        }
                    }
                }
                if (result == null) result = loader.load(token);
                Consumer<MapTileCacheResult> success;
                synchronized (MapTileScheduler.this) {
                    pending.remove(key, this);
                    if (cancelled || (key != null && requestId != ownerRequestId)) return;
                    success = onSuccess;
                }
                success.accept(result);
            } catch (Exception failure) {
                Consumer<Throwable> failed;
                synchronized (MapTileScheduler.this) {
                    pending.remove(key, this);
                    if (cancelled || (key != null && requestId != ownerRequestId)) return;
                    failed = onFailure;
                }
                failed.accept(failure);
            } finally {
                synchronized (MapTileScheduler.this) { if (!handedOff) running.remove(this); }
            }
        }

        private void cancel() {
            cancelled = true;
            executor.remove(this);
        }

        @Override
        public int compareTo(ScheduledTile other) {
            int byStage = Boolean.compare(other.preview != null, preview != null);
            if (byStage != 0) return byStage;
            int byPriority = Long.compare(priority, other.priority);
            return byPriority != 0
                    ? byPriority
                    : Long.compare(sequenceNumber, other.sequenceNumber);
        }
    }

    @FunctionalInterface
    public interface TileLoader {
        MapTileCacheResult load(Cancellation cancellation) throws Exception;
    }

    @FunctionalInterface
    public interface Cancellation {
        boolean isCancelled();
        default boolean isObsolete() { return false; }
        default boolean needsChunk(int x, int z) { return true; }
    }
}
