package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.resource.TextureAtlas;
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
        return put(key, snapshot, signature, Optional.empty(), 0);
    }

    public synchronized boolean put(ViewerAreaKey key, VoxelSceneSnapshot snapshot,
                                    RegionSourceSignature signature,
                                    Optional<ViewerAreaThumbnail> thumbnail, int failedChunks) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(signature, "signature");
        ViewerCachedArea preview = new ViewerCachedArea(key, thumbnail, failedChunks);
        long bytes = Math.addExact(estimatedBytes(snapshot), thumbnail.map(ViewerAreaThumbnail::estimatedBytes).orElse(0L));
        if (bytes > maxBytes) {
            remove(key);
            return false;
        }
        Entry previous = entries.remove(key);
        if (previous != null) {
            currentBytes -= previous.estimatedBytes();
        }
        entries.put(key, new Entry(snapshot, signature, bytes, preview));
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

    public synchronized List<ViewerCachedArea> previews() {
        return entries.values().stream().map(Entry::preview).toList().reversed();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long currentBytes() {
        return currentBytes;
    }

    public static long estimatedBytes(VoxelSceneSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long vertexBytes = snapshot.chunks().stream().flatMap(chunk -> chunk.batches().stream())
                .mapToLong(batch -> Math.multiplyExact((long) batch.vertexCount(), (batch.textured() ? 8L : 6L) * Float.BYTES)).sum();
        long indexBytes = Math.multiplyExact((long) snapshot.indexCount(), Integer.BYTES);
        long targets = snapshot.chunks().stream().mapToLong(chunk -> chunk.targets().estimatedBytes()).sum();
        long owners = snapshot.confirmedModIds().stream().mapToLong(id -> 64L + id.length()*2L).sum();
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<com.mcworldexplorer.voxel.resource.BlockResourceDiagnostic,Boolean>());
        var seenWarnings=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<com.mcworldexplorer.voxel.mesh.MeshWarning,Boolean>());
        var warningLists=new java.util.ArrayList<java.util.List<com.mcworldexplorer.voxel.mesh.MeshWarning>>();
        warningLists.add(snapshot.warnings());snapshot.chunks().forEach(chunk->warningLists.add(chunk.warnings()));
        long diagnostics=0;
        for(var warnings:warningLists)for(var warning:warnings){
            if(!seenWarnings.add(warning))continue;
            diagnostics+=80L+warning.detail().length()*2L;
            if(warning.diagnostic().isPresent()&&seen.add(warning.diagnostic().orElseThrow())){
                var d=warning.diagnostic().orElseThrow();diagnostics+=192L+d.state().name().length()*2L+d.detail().length()*2L+d.reasonCode().length()*2L;
                diagnostics+=d.state().properties().entrySet().stream().mapToLong(e->64L+(e.getKey().length()+e.getValue().length())*2L).sum();
                diagnostics+=d.loaders().stream().mapToLong(value->40L+value.length()*2L).sum();
                diagnostics+=d.assets().stream().mapToLong(a->96L+(a.kind().length()+a.id().length()+a.origin().length())*2L).sum();
            }
        }
        return Math.addExact(Math.addExact(Math.addExact(vertexBytes, indexBytes), targets + owners + diagnostics), snapshot.atlas().map(TextureAtlas::estimatedBytes).orElse(0L));
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
            long estimatedBytes,
            ViewerCachedArea preview) {
    }
}
