package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTileMemoryCacheTest {
    @Test
    void evictsLeastRecentlyUsedEntry() {
        MapTileMemoryCache cache = new MapTileMemoryCache(2);
        MapTileKey first = key(1);
        MapTileKey second = key(2);
        MapTileKey third = key(3);
        cache.put(first, value(first));
        cache.put(second, value(second));

        assertTrue(cache.get(first).isPresent());
        cache.put(third, value(third));

        assertTrue(cache.get(first).isPresent());
        assertFalse(cache.get(second).isPresent());
        assertTrue(cache.get(third).isPresent());
        assertEquals(2, cache.size());
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
}
