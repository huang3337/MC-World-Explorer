package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceRangeExportTest {
    @TempDir Path world;

    @Test
    void reversedCornersCrossNegativeTileAndChunkBoundariesAtNativeResolution() throws IOException {
        var dimension = WorldDimension.overworld(world);
        Path west = dimension.regionDirectory().resolve("r.-1.0.mca");
        Path east = dimension.regionDirectory().resolve("r.0.0.mca");
        MapSamplingFixtures.region(west, "minecraft:stone", 0, 1, 31);
        MapSamplingFixtures.region(east, "minecraft:grass_block", 1, 1, 0);
        byte[] westBefore = Files.readAllBytes(west), eastBefore = Files.readAllBytes(east);
        var range = SurfaceRangeExport.fromCorners(15, 7, -16, -8);
        assertEquals(range, SurfaceRangeExport.fromCorners(-16, -8, 15, 7));
        var image = range.render(dimension, () -> false);
        assertEquals(32, image.getWidth());
        assertEquals(16, image.getHeight());
        var info = new WorldInfo(world);
        var generator = new MapTileGenerator();
        for (int tileX = -1; tileX <= 0; tileX++) {
            var key = new MapTileKey(WorldCachePaths.worldDirectoryName(info), dimension.id(),
                    PreviewLayer.surfaceOverview(), MapZoomLevel.BLOCKS_1, tileX, 0, "test");
            var reference = generator.generate(info, dimension, key, MapTileGenerationMonitor.NONE).image();
            for (int z = 0; z <= 7; z++) {
                for (int x = tileX * 16; x < (tileX + 1) * 16; x++) {
                    assertEquals(reference.getRGB(Math.floorMod(x, 256), z), image.getRGB(x + 16, z + 8));
                }
            }
        }
        assertEquals(0xff20262b, image.getRGB(16, 0));
        assertNotEquals(image.getRGB(16, 8), image.getRGB(17, 8), "瓦片接缝应使用西侧方块高度");
        assertArrayEquals(westBefore, Files.readAllBytes(west));
        assertArrayEquals(eastBefore, Files.readAllBytes(east));
    }

    @Test
    void readsOnlySelectedDimensionAndLeavesSparseGapAtTrueWidth() throws IOException {
        var nether = WorldDimension.nether(world);
        MapSamplingFixtures.region(nether.regionDirectory().resolve("r.0.0.mca"), "minecraft:stone", 0, 1, 0, 2);
        MapSamplingFixtures.region(WorldDimension.overworld(world).regionDirectory().resolve("r.0.0.mca"),
                "minecraft:grass_block", 0, 1, 1);
        var image = SurfaceRangeExport.fromCorners(0, 0, 47, 0).render(nether, () -> false);
        int stone = 0xff000000 | BlockColorPalette.resolve("minecraft:stone").rgb();
        for (int x = 0; x < 48; x++) assertEquals(x >= 16 && x < 32 ? 0xff20262b : stone, image.getRGB(x, 0));
    }

    @Test
    void reportsUnreadableChunkAndShadingDependencyButIgnoresUnrelatedDamage() throws IOException {
        var dimension = WorldDimension.overworld(world);
        Path region = dimension.regionDirectory().resolve("r.0.0.mca");
        MapSamplingFixtures.region(region, "minecraft:stone", 0, 1, 0, 1, 32, 33);
        byte[] bytes = Files.readAllBytes(region);
        ByteBuffer.wrap(bytes).putInt(0, 1);
        Files.write(region, bytes);
        for (int[] position : new int[][]{{15, 15}, {16, 15}, {15, 16}}) {
            var error = assertThrows(IOException.class, () -> SurfaceRangeExport
                    .fromCorners(position[0], position[1], position[0], position[1]).render(dimension, () -> false));
            assertTrue(error.getMessage().contains("区块 (0, 0)"));
        }
        assertDoesNotThrow(() -> SurfaceRangeExport.fromCorners(16, 16, 16, 16).render(dimension, () -> false));
        Files.write(region, new byte[10]);
        var error = assertThrows(IOException.class, () -> SurfaceRangeExport.fromCorners(0, 0, 1, 1)
                .render(dimension, () -> false));
        assertTrue(error.getMessage().contains("r.0.0.mca"));
    }

    @Test
    void validatesInclusiveDimensionsWithoutOverflowAndCanCancelDuringWork() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> SurfaceRangeExport.fromCorners(0, 0, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> SurfaceRangeExport.fromCorners(0, Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
        var range = SurfaceRangeExport.fromCorners(Integer.MAX_VALUE - 1023, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE + 1023);
        var image = range.render(WorldDimension.overworld(world), () -> false);
        assertEquals(1024, image.getWidth());
        assertEquals(1024, image.getHeight());
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> range.render(WorldDimension.overworld(world),
                () -> checks.incrementAndGet() == 4));
    }
}
