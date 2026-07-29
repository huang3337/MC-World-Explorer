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
        Optional<MapTileCacheResult> inMemory = memoryCache.get(key);
        if (inMemory.isPresent()) {
            return inMemory.orElseThrow();
        }
        Optional<MapTileCacheResult> onDisk = diskCache.findReusable(world, dimension, key);
        if (onDisk.isPresent()) {
            MapTileCacheResult result = onDisk.orElseThrow();
            memoryCache.put(key, result);
            return result;
        }
        MapTileGenerationResult generated = generator.generate(world, dimension, key, monitor);
        if (monitor.isCancelled()) {
            throw new CancellationException("map tile generation cancelled before cache write");
        }
        MapTileCacheResult stored = diskCache.store(world, dimension, key, generated);
        memoryCache.put(key, stored);
        return stored;
    }

    public void clearMemory() {
        memoryCache.clear();
    }
}
