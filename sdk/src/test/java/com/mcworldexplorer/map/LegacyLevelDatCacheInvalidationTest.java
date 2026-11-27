package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyLevelDatCacheInvalidationTest {
    @TempDir
    Path worldPath;

    @Test
    void levelDatChangeInvalidatesMapTileSummaryCache() throws Exception {
        MapSamplingFixtures.region(worldPath.resolve("region/r.0.0.mca"), "minecraft:stone", 0, 1, 0);
        Path levelDat = worldPath.resolve("level.dat");
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", CompoundBinaryTag.builder().putString("LevelName", "CacheTest").build())
                .build();
        BinaryTagIO.writer().write(root, levelDat, BinaryTagIO.Compression.GZIP);

        var dimension = WorldDimension.overworld(worldPath);
        var world = new WorldInfo(worldPath);
        var key = new MapTileKey(
                WorldCachePaths.worldDirectoryName(world),
                dimension.id(),
                PreviewLayer.surfaceOverview(),
                MapZoomLevel.fromBlocksPerPixel(1),
                0,
                0,
                "cp4-test");
        var generator = new MapTileGenerator();

        generator.generate(world, dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(1, generator.decodeCount());

        // Second run with unchanged level.dat hits memory summary cache
        generator.generate(world, dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(1, generator.decodeCount());

        // Touch level.dat -> cache must miss and re-decode
        FileTime mtime = Files.getLastModifiedTime(levelDat);
        Files.setLastModifiedTime(levelDat, FileTime.fromMillis(mtime.toMillis() + 5000L));
        generator.generate(world, dimension, key, MapTileGenerationMonitor.NONE);
        assertEquals(2, generator.decodeCount());
    }
}
