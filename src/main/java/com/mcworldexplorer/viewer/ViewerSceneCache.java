package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ViewerSceneCache {
    private final LinkedHashMap<ViewerAreaKey, Entry> entries =
            new LinkedHashMap<>(16, 0.75f, true);
    private int maxEntries;
    private long maxBytes;
    private long currentBytes;

    public ViewerSceneCache(ViewerPerformanceProfile profile) {
        setProfile(profile);
    }

    ViewerSceneCache(int maxEntries, long maxBytes) {
        setLimits(maxEntries, maxBytes);
    }

    public synchronized void setProfile(ViewerPerformanceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        setLimits(profile.maxCachedScenes(), profile.maxCachedBytes());
    }

    public synchronized Optional<VoxelSceneSnapshot> get(
            ViewerAreaKey key,
            RegionSourceSignature currentSignature) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(currentSignature, "currentSignature");
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.signature().equals(currentSignature)) {
            remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    public synchronized boolean put(
            ViewerAreaKey key,
            VoxelSceneSnapshot snapshot,
            RegionSourceSignature signature) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(signature, "signature");
        long bytes = estimatedBytes(snapshot);
        if (bytes > maxBytes) {
            remove(key);
            return false;
        }
        Entry previous = entries.remove(key);
        if (previous != null) {
            currentBytes -= previous.estimatedBytes();
        }
        entries.put(key, new Entry(snapshot, signature, bytes));
        currentBytes += bytes;
        evictToLimits();
        return entries.containsKey(key);
    }

    public synchronized void remove(ViewerAreaKey key) {
        Entry removed = entries.remove(key);
        if (removed != null) {
            currentBytes -= removed.estimatedBytes();
        }
    }

    public synchronized void clear() {
        entries.clear();
        currentBytes = 0;
    }

    public synchronized List<ViewerAreaKey> keys() {
        return List.copyOf(entries.keySet());
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long currentBytes() {
        return currentBytes;
    }

    public static long estimatedBytes(VoxelSceneSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long vertexBytes = Math.multiplyExact((long) snapshot.vertexCount(), 6L * Float.BYTES);
        long indexBytes = Math.multiplyExact((long) snapshot.indexCount(), Integer.BYTES);
        return Math.addExact(vertexBytes, indexBytes);
    }

    private void setLimits(int maxEntries, long maxBytes) {
        if (maxEntries < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        evictToLimits();
    }

    private void evictToLimits() {
        var iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || currentBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<ViewerAreaKey, Entry> eldest = iterator.next();
            currentBytes -= eldest.getValue().estimatedBytes();
            iterator.remove();
        }
    }

    private record Entry(
            VoxelSceneSnapshot snapshot,
            RegionSourceSignature signature,
            long estimatedBytes) {
    }
}
