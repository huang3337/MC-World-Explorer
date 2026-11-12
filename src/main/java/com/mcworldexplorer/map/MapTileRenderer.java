package com.mcworldexplorer.map;

import java.awt.image.BufferedImage;

public final class MapTileRenderer {
    private static final int MISSING_COLOR = 0xFF20262B;
    private static final int STRIDE = MapTileBounds.TILE_PIXELS + 1;
    private static final int PIXEL_COUNT = STRIDE * STRIDE;

    private final long[] redSums = new long[PIXEL_COUNT];
    private final long[] greenSums = new long[PIXEL_COUNT];
    private final long[] blueSums = new long[PIXEL_COUNT];
    private final long[] heightSums = new long[PIXEL_COUNT];
    private final int[] counts = new int[PIXEL_COUNT];

    public void accept(
            MapTileBounds bounds,
            MapZoomLevel zoom,
            long worldX,
            long worldZ,
            int rgb,
            int height) {
        acceptAggregate(bounds, zoom, worldX, worldZ, rgb >>> 16 & 255, rgb >>> 8 & 255,
                rgb & 255, height, 1);
    }

    void acceptAggregate(MapTileBounds bounds, MapZoomLevel zoom, long worldX, long worldZ,
                         long red, long green, long blue, long height, int count) {
        long x = Math.floorDiv(worldX - bounds.minX(), zoom.blocksPerPixel());
        long z = Math.floorDiv(worldZ - bounds.minZ(), zoom.blocksPerPixel());
        if (x < -1 || z < -1 || x >= 256 || z >= 256 || (x == -1 && z == -1)) return;
        int index = (int) (x + 1 + (z + 1) * STRIDE);
        redSums[index] += red; greenSums[index] += green; blueSums[index] += blue;
        heightSums[index] += height; counts[index] += count;
    }

    public BufferedImage render() {
        return render(false);
    }

    public BufferedImage renderPartial() {
        return render(true);
    }

    private BufferedImage render(boolean transparentMissing) {
        int[] baseColors = new int[PIXEL_COUNT];
        int[] averageHeights = new int[PIXEL_COUNT];
        for (int index = 0; index < PIXEL_COUNT; index++) {
            int count = counts[index];
            if (count > 0) {
                baseColors[index] = (int) (redSums[index] / count) << 16
                        | (int) (greenSums[index] / count) << 8
                        | (int) (blueSums[index] / count);
                averageHeights[index] = Math.toIntExact(Math.floorDiv(heightSums[index], count));
            }
        }

        BufferedImage image = new BufferedImage(
                MapTileBounds.TILE_PIXELS,
                MapTileBounds.TILE_PIXELS,
                BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < MapTileBounds.TILE_PIXELS; z++) {
            for (int x = 0; x < MapTileBounds.TILE_PIXELS; x++) {
                int index = x + 1 + (z + 1) * STRIDE;
                if (counts[index] == 0) {
                    image.setRGB(x, z, transparentMissing ? 0 : MISSING_COLOR);
                    continue;
                }
                int neighborHeight = averageHeights[index];
                int neighborCount = 0;
                if (counts[index - 1] > 0) {
                    neighborHeight += averageHeights[index - 1];
                    neighborCount++;
                }
                if (counts[index - STRIDE] > 0) {
                    neighborHeight += averageHeights[index - STRIDE];
                    neighborCount++;
                }
                double shade = 1.0;
                if (neighborCount > 0) {
                    int referenceHeight = Math.floorDiv(
                            neighborHeight - averageHeights[index],
                            neighborCount);
                    int difference = averageHeights[index] - referenceHeight;
                    shade += Math.max(-0.22, Math.min(0.22, difference * 0.035));
                }
                image.setRGB(x, z, 0xFF000000 | shade(baseColors[index], shade));
            }
        }
        return image;
    }

    private static int shade(int color, double factor) {
        int red = clamp((int) Math.round((color >>> 16 & 0xFF) * factor));
        int green = clamp((int) Math.round((color >>> 8 & 0xFF) * factor));
        int blue = clamp((int) Math.round((color & 0xFF) * factor));
        return red << 16 | green << 8 | blue;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
