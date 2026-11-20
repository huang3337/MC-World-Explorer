package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.BlockColorPalette;

import java.awt.image.BufferedImage;

public final class MapTileRenderer {
    public static final int MISSING_COLOR = 0xFF20262B;
    private static final int STRIDE = MapTileBounds.TILE_PIXELS + 1;
    private static final int PIXEL_COUNT = STRIDE * STRIDE;

    private long[] redSums, greenSums, blueSums, heightSums;
    private int[] counts;

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
        if (counts == null) {
            redSums = new long[PIXEL_COUNT];
            greenSums = new long[PIXEL_COUNT];
            blueSums = new long[PIXEL_COUNT];
            heightSums = new long[PIXEL_COUNT];
            counts = new int[PIXEL_COUNT];
        }
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
        BufferedImage image = new BufferedImage(
                MapTileBounds.TILE_PIXELS,
                MapTileBounds.TILE_PIXELS,
                BufferedImage.TYPE_INT_ARGB);
        if (counts == null) {
            if (!transparentMissing) {
                var pixels = (java.awt.image.DataBufferInt) image.getRaster().getDataBuffer();
                java.util.Arrays.fill(pixels.getData(), MISSING_COLOR);
            }
            return image;
        }
        int[] northHeights = new int[MapTileBounds.TILE_PIXELS];
        int[] row = new int[MapTileBounds.TILE_PIXELS];
        for (int x = 0; x < northHeights.length; x++) northHeights[x] = averageHeight(x + 1);
        for (int z = 0; z < MapTileBounds.TILE_PIXELS; z++) {
            int westHeight = averageHeight((z + 1) * STRIDE);
            for (int x = 0; x < MapTileBounds.TILE_PIXELS; x++) {
                int index = x + 1 + (z + 1) * STRIDE;
                int count = counts[index];
                if (count == 0) {
                    row[x] = transparentMissing ? 0 : MISSING_COLOR;
                    continue;
                }
                int height = averageHeight(index);
                int neighborHeight = 0;
                int neighborCount = 0;
                if (counts[index - 1] > 0) {
                    neighborHeight += westHeight;
                    neighborCount++;
                }
                if (counts[index - STRIDE] > 0) {
                    neighborHeight += northHeights[x];
                    neighborCount++;
                }
                double shade = 1.0;
                if (neighborCount > 0) {
                    int referenceHeight = Math.floorDiv(neighborHeight, neighborCount);
                    int difference = height - referenceHeight;
                    shade += Math.max(-0.22, Math.min(0.22, difference * 0.035));
                }
                int color = (int) (redSums[index] / count) << 16
                        | (int) (greenSums[index] / count) << 8
                        | (int) (blueSums[index] / count);
                row[x] = 0xFF000000 | BlockColorPalette.shade(color, shade);
                westHeight = height;
                northHeights[x] = height;
            }
            image.getRaster().setDataElements(0, z, row.length, 1, row);
        }
        return image;
    }

    private int averageHeight(int index) {
        return counts[index] == 0 ? 0 : Math.toIntExact(Math.floorDiv(heightSums[index], counts[index]));
    }
}
