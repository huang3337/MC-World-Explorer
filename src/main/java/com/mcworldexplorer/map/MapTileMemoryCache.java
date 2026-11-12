package com.mcworldexplorer.map;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MapTileMemoryCache {
    public static final int DEFAULT_CAPACITY = 64;

    public static final long MAX_BYTES = 16L << 20;
    private final int capacity;
    private final Map<MapTileKey, MapTileCacheResult> entries =
            new LinkedHashMap<>(16, 0.75f, true);

    public MapTileMemoryCache() {
        this(DEFAULT_CAPACITY);
    }

    MapTileMemoryCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized Optional<MapTileCacheResult> get(MapTileKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    public synchronized void put(MapTileKey key, MapTileCacheResult value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key and value must not be null");
        }
        entries.put(key, value);
        while (entries.size() > capacity || estimatedBytes() > MAX_BYTES) {
            MapTileKey eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    public synchronized void remove(MapTileKey key) { entries.remove(key); }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized long estimatedBytes() {
        return entries.entrySet().stream().mapToLong(entry -> {
            var value = entry.getValue();
            return MapTileCache.retainedBytes(value.image(), value.markers(),
                    new MapTileCache.Snapshot(value.identity(), value.sources()), entry.getKey());
        }).sum();
    }

    synchronized int size() {
        return entries.size();
    }
}
