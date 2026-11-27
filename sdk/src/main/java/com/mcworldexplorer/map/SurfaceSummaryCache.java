package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.region.RegionHeaderIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 不创建线程；由现有地图工作线程执行，按来源合并在途读取。 */
final class SurfaceSummaryCache {
    static final long DEFAULT_BYTES = 52L * 1024 * 1024;
    private static final int MAX_IN_FLIGHT = 256;
    record Key(Path world, String dimension, int x, int z, PreviewLayer layer,
               RegionHeaderIndex.Signature source, String rulesVersion) { }
    private record VersionedKey(long epoch, Key key) { }
    record Statistics(long loads, long hits, long joins, long bytes, int entries, int inFlight) { }
    @FunctionalInterface interface Loader { ChunkSurfaceSummary load() throws IOException; }

    private final long limit;
    private final LinkedHashMap<VersionedKey, ChunkSurfaceSummary> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<VersionedKey, CompletableFuture<ChunkSurfaceSummary>> inFlight = new HashMap<>();
    private long epoch, bytes, loads, hits, joins;

    SurfaceSummaryCache() { this(DEFAULT_BYTES); }
    SurfaceSummaryCache(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative summary budget");
        this.limit = limit;
    }

    synchronized long epoch() { return epoch; }
    synchronized boolean isCurrent(long value) { return value == epoch; }
    synchronized Statistics statistics() { return new Statistics(loads, hits, joins, bytes, entries.size(), inFlight.size()); }

    synchronized void clear() {
        epoch++;
        entries.clear();
        bytes = 0;
        // 老批次仍占在途配额，直到其单区块读取结束；不误杀其他消费者。
        notifyAll();
    }

    ChunkSurfaceSummary get(Key key, long requestEpoch, MapTileGenerationMonitor monitor, Loader loader) throws IOException {
        VersionedKey versioned = new VersionedKey(requestEpoch, key);
        CompletableFuture<ChunkSurfaceSummary> future;
        boolean owner = false;
        synchronized (this) {
            while (true) {
                checkCancelled(requestEpoch, monitor);
                ChunkSurfaceSummary cached = entries.get(versioned);
                if (cached != null) { hits++; return cached; }
                future = inFlight.get(versioned);
                if (future != null) { joins++; break; }
                if (inFlight.size() < MAX_IN_FLIGHT) {
                    future = new CompletableFuture<>();
                    inFlight.put(versioned, future);
                    loads++;
                    owner = true;
                    break;
                }
                try { wait(25); } catch (InterruptedException interrupted) { throw interrupted(interrupted); }
            }
        }
        if (owner) {
            try {
                ChunkSurfaceSummary result = loader.load();
                synchronized (this) {
                    if (requestEpoch == epoch && result.estimatedBytes() <= limit) {
                        while (bytes + result.estimatedBytes() > limit && !entries.isEmpty()) {
                            var iterator = entries.entrySet().iterator();
                            bytes -= iterator.next().getValue().estimatedBytes();
                            iterator.remove();
                        }
                        entries.put(versioned, result);
                        bytes += result.estimatedBytes();
                    }
                    // 完成 future 后才移除，零缓存预算下也不出现重复在途读取窗口。
                    future.complete(result);
                    inFlight.remove(versioned);
                    notifyAll();
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    future.completeExceptionally(failure);
                    inFlight.remove(versioned);
                    notifyAll();
                }
                if (failure instanceof Error error) throw error;
            }
        }
        while (true) {
            synchronized (this) { checkCancelled(requestEpoch, monitor); }
            try {
                ChunkSurfaceSummary result = future.get(25, TimeUnit.MILLISECONDS);
                synchronized (this) { checkCancelled(requestEpoch, monitor); }
                return result;
            } catch (TimeoutException ignored) {
                // 等待者独立取消，不取消共享 future。
            } catch (InterruptedException interrupted) {
                throw interrupted(interrupted);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof IOException io) throw io;
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failed.getCause() instanceof Error error) throw error;
                throw new IOException("Summary sampling failed", failed.getCause());
            }
        }
    }

    private void checkCancelled(long requestEpoch, MapTileGenerationMonitor monitor) {
        if (requestEpoch != epoch || monitor.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Map summary request cancelled");
        }
    }

    private static CancellationException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        CancellationException cancelled = new CancellationException("Interrupted while waiting for map summary");
        cancelled.initCause(cause);
        return cancelled;
    }
}
