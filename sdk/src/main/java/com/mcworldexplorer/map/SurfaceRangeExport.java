package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.SurfaceSampler;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.region.RegionHeaderIndex;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 当前维度的地表底图。两端都是输出图中的方块。 */
public record SurfaceRangeExport(int minX, int minZ, int maxX, int maxZ) {
    public static final int MAX_SIDE = 1024;

    public SurfaceRangeExport {
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("坐标范围无效");
        long width = (long) maxX - minX + 1;
        long height = (long) maxZ - minZ + 1;
        if (width > MAX_SIDE || height > MAX_SIDE) {
            String side = width > MAX_SIDE && height > MAX_SIDE ? "宽和高" : width > MAX_SIDE ? "宽" : "高";
            throw new IllegalArgumentException(width + " × " + height + " 像素，请将" + side + "缩小到 " + MAX_SIDE + " 以内");
        }
    }

    public static SurfaceRangeExport fromCorners(int x1, int z1, int x2, int z2) {
        return new SurfaceRangeExport(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public int width() { return maxX - minX + 1; }
    public int height() { return maxZ - minZ + 1; }

    public BufferedImage render(WorldDimension dimension, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        var output = new BufferedImage(width(), height(), BufferedImage.TYPE_INT_ARGB);
        var sources = new LinkedHashMap<Path, RegionHeaderIndex.Signature>();
        var sampler = new SurfaceSampler();
        Graphics2D graphics = output.createGraphics();
        try {
            for (int tz = Math.floorDiv(minZ, 256); tz <= Math.floorDiv(maxZ, 256); tz++) {
                for (int tx = Math.floorDiv(minX, 256); tx <= Math.floorDiv(maxX, 256); tx++) {
                    checkCancelled(cancelled);
                    var tile = MapTileBounds.of(tx, tz, MapZoomLevel.BLOCKS_1);
                    var image = renderTile(tile, dimension.regionDirectory(), sources, sampler, cancelled);
                    graphics.drawImage(image, (int) (tile.minX() - minX), (int) (tile.minZ() - minZ), null);
                }
            }
        } finally {
            graphics.dispose();
        }
        for (var source : sources.entrySet()) {
            checkCancelled(cancelled);
            if (!Objects.equals(source.getValue(), signature(source.getKey()))) {
                throw new IOException("导出期间地形已改变，请重试：" + source.getKey().getFileName());
            }
        }
        checkCancelled(cancelled);
        return output;
    }

    private BufferedImage renderTile(MapTileBounds tile, Path directory,
            Map<Path, RegionHeaderIndex.Signature> sources, SurfaceSampler sampler,
            BooleanSupplier cancelled) throws IOException {
        long left = Math.max(minX, tile.minX()), top = Math.max(minZ, tile.minZ());
        int x0 = chunk(left - 1), z0 = chunk(top - 1);
        int x1 = chunk(Math.min(maxX, tile.maxXExclusive() - 1));
        int z1 = chunk(Math.min(maxZ, tile.maxZExclusive() - 1));
        var renderer = new MapTileRenderer();
        // 着色依赖西侧、北侧各一列方块，西北角不参与任何输出像素。
        for (int rz = Math.floorDiv(z0, 32); rz <= Math.floorDiv(z1, 32); rz++) {
            for (int rx = Math.floorDiv(x0, 32); rx <= Math.floorDiv(x1, 32); rx++) {
                int endX = Math.min(x1, rx * 32 + 31), endZ = Math.min(z1, rz * 32 + 31);
                if (endX < chunk(left) && endZ < chunk(top)) continue;
                Path path = directory.resolve("r." + rx + "." + rz + ".mca");
                checkCancelled(cancelled);
                if (!sources.containsKey(path)) sources.put(path, signature(path));
                if (sources.get(path) == null) continue;
                try (var reader = new RegionFileReader(path)) {
                    for (int z = Math.max(z0, rz * 32); z <= endZ; z++) {
                        for (int x = Math.max(x0, rx * 32); x <= endX; x++) {
                            if (x < chunk(left) && z < chunk(top)) continue;
                            checkCancelled(cancelled);
                            try {
                                var data = reader.readChunk(Math.floorMod(x, 32), Math.floorMod(z, 32));
                                if (data.isEmpty()) continue;
                                var summary = new ChunkSurfaceSummary(sampler.sample(data.get()), List.of(), "");
                                summary.renderInto(renderer, tile, MapZoomLevel.BLOCKS_1, x, z);
                            } catch (IOException | RuntimeException error) {
                                throw new IOException("区块 (" + x + ", " + z + ") 读取失败：" + error.getMessage(), error);
                            }
                        }
                    }
                } catch (IOException error) {
                    throw new IOException(path.getFileName() + "：" + error.getMessage(), error);
                }
            }
        }
        return renderer.render();
    }

    private static int chunk(long block) { return Math.toIntExact(Math.floorDiv(block, 16)); }

    private static RegionHeaderIndex.Signature signature(Path path) throws IOException {
        try {
            return RegionHeaderIndex.readSignature(path);
        } catch (NoSuchFileException missing) {
            if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("无法核实区域 " + path.getFileName(), missing);
            }
            return null;
        } catch (IOException error) {
            throw new IOException("无法读取区域 " + path.getFileName() + "：" + error.getMessage(), error);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("已取消范围导出");
        }
    }
}
