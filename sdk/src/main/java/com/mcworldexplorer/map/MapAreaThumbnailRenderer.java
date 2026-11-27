package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.preview.SurfaceSampler;
import com.mcworldexplorer.preview.SurfaceSamplingException;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** A cropped 2D surface map, independent of the 3D classifier and fallback materials. */
public final class MapAreaThumbnailRenderer {
    private record Tile(long x, long z) { }

    public BufferedImage render(VoxelAreaLoadResult area, CancellationSignal cancellation) {
        var target = area.target();
        if (!target.isSupportedTarget()) throw new IllegalArgumentException("unsupported thumbnail target");
        MapZoomLevel zoom = MapZoomLevel.BLOCKS_2;
        long minX = (long) target.minX() * 16;
        long minZ = (long) target.minZ() * 16;
        long maxX = minX + target.width() * 16L;
        long maxZ = minZ + target.depth() * 16L;
        Map<Tile, MapTileRenderer> renderers = new HashMap<>();
        for (long z = MapTileKey.tileCoordinate(minZ, zoom); z <= MapTileKey.tileCoordinate(maxZ - 1, zoom); z++) {
            for (long x = MapTileKey.tileCoordinate(minX, zoom); x <= MapTileKey.tileCoordinate(maxX - 1, zoom); x++) {
                renderers.put(new Tile(x, z), new MapTileRenderer());
            }
        }
        SurfaceSampler sampler = new SurfaceSampler();
        for (var chunk : area.results().values()) {
            checkCancellation(cancellation);
            if (chunk.status() != ChunkLoadStatus.SUCCESS) continue;
            try {
                // Include the already-read halo so 2D height shading at the crop edge remains correct.
                sampler.forEachSurfaceColumn(chunk.chunk(), (x, z, column) -> {
                    long worldX = (long) chunk.coordinate().x() * 16 + x;
                    long worldZ = (long) chunk.coordinate().z() * 16 + z;
                    Tile tile = new Tile(MapTileKey.tileCoordinate(worldX, zoom), MapTileKey.tileCoordinate(worldZ, zoom));
                    MapTileRenderer renderer = renderers.get(tile);
                    if (renderer != null) renderer.accept(MapTileBounds.of(tile.x(), tile.z(), zoom), zoom,
                            worldX, worldZ, BlockColorPalette.resolve(column.blockName()).rgb(), column.y());
                });
            } catch (SurfaceSamplingException ignored) {
                // Match missing/corrupt map regions; do not substitute a 3D fallback color.
            }
        }
        Map<Tile, BufferedImage> images = new HashMap<>();
        renderers.forEach((key, renderer) -> images.put(key, renderer.render()));
        BufferedImage output = new BufferedImage(160, 100, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.min(160.0 / (maxX - minX), 100.0 / (maxZ - minZ));
        int width = Math.max(1, (int) Math.round((maxX - minX) * scale));
        int height = Math.max(1, (int) Math.round((maxZ - minZ) * scale));
        int left = (160 - width) / 2;
        int top = (100 - height) / 2;
        for (int y = 0; y < 100; y++) {
            checkCancellation(cancellation);
            for (int x = 0; x < 160; x++) {
                int color = 0xffe8edf2;
                if (x >= left && x < left + width && y >= top && y < top + height) {
                    long worldX = Math.min(maxX - 1, minX + (long) ((x - left + 0.5) / scale));
                    long worldZ = Math.min(maxZ - 1, minZ + (long) ((y - top + 0.5) / scale));
                    Tile tile = new Tile(MapTileKey.tileCoordinate(worldX, zoom), MapTileKey.tileCoordinate(worldZ, zoom));
                    var bounds = MapTileBounds.of(tile.x(), tile.z(), zoom);
                    color = images.get(tile).getRGB((int) ((worldX - bounds.minX()) / zoom.blocksPerPixel()),
                            (int) ((worldZ - bounds.minZ()) / zoom.blocksPerPixel()));
                }
                output.setRGB(x, y, color);
            }
        }
        return output;
    }

    private static void checkCancellation(CancellationSignal cancellation) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted())
            throw new CancellationException("map thumbnail generation cancelled");
    }
}
