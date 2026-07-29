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

    private final AtomicLong sequence = new AtomicLong();
    private final ThreadPoolExecutor executor;
    private final Map<MapTileKey, ScheduledTile> pending = new HashMap<>();
    private long requestId;

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
        pending.entrySet().removeIf(entry -> {
            if (retainedKeys.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().cancel();
            return true;
        });
        return requestId;
    }

    public synchronized void submit(
            MapTileKey key,
            long priority,
            long ownerRequestId,
            TileLoader loader,
            Consumer<MapTileCacheResult> onSuccess,
            Consumer<Throwable> onFailure) {
        ScheduledTile existing = pending.get(key);
        if (existing != null) {
            existing.rebind(priority, ownerRequestId, onSuccess, onFailure);
            if (executor.remove(existing)) {
                executor.execute(existing);
            }
            return;
        }
        ScheduledTile task = new ScheduledTile(
                key,
                priority,
                sequence.getAndIncrement(),
                ownerRequestId,
                loader,
                onSuccess,
                onFailure);
        pending.put(key, task);
        executor.execute(task);
    }

    public synchronized boolean isCurrent(long candidateRequestId) {
        return requestId == candidateRequestId;
    }

    public synchronized void cancelAll() {
        requestId++;
        pending.values().forEach(ScheduledTile::cancel);
        pending.clear();
    }

    @Override
    public synchronized void close() {
        cancelAll();
        executor.shutdownNow();
    }

    private final class ScheduledTile implements Runnable, Comparable<ScheduledTile> {
        private final MapTileKey key;
        private long priority;
        private final long sequenceNumber;
        private long ownerRequestId;
        private final TileLoader loader;
        private Consumer<MapTileCacheResult> onSuccess;
        private Consumer<Throwable> onFailure;
        private volatile boolean cancelled;

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
            try {
                MapTileCacheResult result = loader.load(() -> cancelled);
                synchronized (MapTileScheduler.this) {
                    pending.remove(key, this);
                    if (cancelled || requestId != ownerRequestId) {
                        return;
                    }
                }
                onSuccess.accept(result);
            } catch (Exception failure) {
                synchronized (MapTileScheduler.this) {
                    pending.remove(key, this);
                    if (cancelled || requestId != ownerRequestId) {
                        return;
                    }
                }
                onFailure.accept(failure);
            }
        }

        private void cancel() {
            cancelled = true;
            executor.remove(this);
        }

        @Override
        public int compareTo(ScheduledTile other) {
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
    }
}
