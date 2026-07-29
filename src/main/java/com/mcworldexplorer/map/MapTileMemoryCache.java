package com.mcworldexplorer.map;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MapTileMemoryCache {
    public static final int DEFAULT_CAPACITY = 128;

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
        while (entries.size() > capacity) {
            MapTileKey eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    public synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }
}
