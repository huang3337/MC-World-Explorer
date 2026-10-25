package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.preview.BlockColorPalette;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ViewerAreaThumbnailTest {
    @Test
    void samplesHighestSurfaceNorthUpAndKeepsRectangularAspectRatio() throws Exception {
        ChunkRectangle target = ChunkRectangle.target(-1, -1, 0, -1);
        Map<ChunkCoordinate, ChunkLoadResult> chunks = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : target.expanded(1).coordinates()) {
            chunks.put(coordinate, ChunkLoadResult.withoutChunk(coordinate, ChunkLoadStatus.MISSING, "", 0, 0));
        }
        BlockState stone = new BlockState("minecraft:stone", Map.of());
        BlockState grass = new BlockState("example:grass_block", Map.of());
        for (int x = -1; x <= 0; x++) {
            var coordinate = new ChunkCoordinate(x, -1);
            var top = x == -1 ? grass : stone;
            var chunk = new VoxelChunk(x, -1, List.of(
                    new ChunkSection(-1, List.of(stone), new long[0]),
                    new ChunkSection(0, List.of(top), new long[0]),
                    new ChunkSection(1, List.of(BlockState.AIR), new long[0])));
            chunks.put(coordinate, ChunkLoadResult.success(coordinate, chunk, 0, 0));
        }
        Path world = Path.of("world").toAbsolutePath();
        var area = new VoxelAreaLoadResult(world, WorldDimension.overworld(world), target, chunks);
        ViewerAreaThumbnail image = ViewerAreaThumbnail.fromArea(area, CancellationSignal.NONE);
        assertEquals(0xE83EED, com.mcworldexplorer.voxel.mesh.BlockClassifier.classify(grass).rgb(),
                "该 Mod 方块在三维仍为未知，缩略图必须独立使用二维配色");
        assertEquals(160, image.width());
        assertEquals(100, image.height());
        assertEquals(0xffe8edf2, image.argb()[0], "长方形上下留白，不能拉伸为正方形");
        assertEquals(0xff000000 | BlockColorPalette.resolve(grass.name()).rgb(), image.argb()[50 * 160 + 30]);
        assertEquals(0xff000000 | BlockColorPalette.resolve(stone.name()).rgb(), image.argb()[50 * 160 + 130]);
        int original = image.argb()[50 * 160 + 30];
        int[] copy = image.argb();
        copy[50 * 160 + 30] = 0;
        assertEquals(original, image.argb()[50 * 160 + 30], "列表不能修改缓存中的像素");
        assertThrows(CancellationException.class, () -> ViewerAreaThumbnail.fromArea(area, () -> true));
    }

    @Test
    void missingChunksStayBlankAndInvalidImageSizesAreRejected() {
        ChunkRectangle target = ChunkRectangle.target(0, 0, 0, 0);
        Map<ChunkCoordinate, ChunkLoadResult> chunks = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : target.expanded(1).coordinates()) {
            chunks.put(coordinate, ChunkLoadResult.withoutChunk(coordinate, ChunkLoadStatus.MISSING, "", 0, 0));
        }
        Path world = Path.of("world").toAbsolutePath();
        var area = new VoxelAreaLoadResult(world, WorldDimension.overworld(world), target, chunks);
        assertTrue(java.util.Arrays.stream(ViewerAreaThumbnail.fromArea(area, CancellationSignal.NONE).argb())
                .allMatch(pixel -> pixel == 0xffe8edf2 || pixel == 0xff20262b));
        assertThrows(IllegalArgumentException.class, () -> new ViewerAreaThumbnail(161, 100, new int[16100]));
    }
}
