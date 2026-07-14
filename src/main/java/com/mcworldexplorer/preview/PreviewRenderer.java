package com.mcworldexplorer.preview;

import java.awt.image.BufferedImage;

final class PreviewRenderer {
    private static final int MISSING_COLOR = 0xFF20262B;

    BufferedImage render(
            int inputWidth,
            int scale,
            int[] blockColors,
            int[] heights,
            boolean[] populated) {
        int expectedLength = inputWidth * inputWidth;
        if (inputWidth <= 0 || scale <= 0 || inputWidth % scale != 0
                || blockColors.length != expectedLength
                || heights.length != expectedLength
                || populated.length != expectedLength) {
            throw new IllegalArgumentException("invalid preview surface arrays");
        }

        int outputWidth = inputWidth / scale;
        int[] baseColors = new int[outputWidth * outputWidth];
        int[] averageHeights = new int[outputWidth * outputWidth];
        boolean[] outputPopulated = new boolean[outputWidth * outputWidth];

        for (int outputZ = 0; outputZ < outputWidth; outputZ++) {
            for (int outputX = 0; outputX < outputWidth; outputX++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                int height = 0;
                int count = 0;
                for (int dz = 0; dz < scale; dz++) {
                    for (int dx = 0; dx < scale; dx++) {
                        int inputX = outputX * scale + dx;
                        int inputZ = outputZ * scale + dz;
                        int inputIndex = inputX + inputZ * inputWidth;
                        if (!populated[inputIndex]) {
                            continue;
                        }
                        int color = blockColors[inputIndex];
                        red += color >>> 16 & 0xFF;
                        green += color >>> 8 & 0xFF;
                        blue += color & 0xFF;
                        height += heights[inputIndex];
                        count++;
                    }
                }

                int outputIndex = outputX + outputZ * outputWidth;
                if (count > 0) {
                    baseColors[outputIndex] = (red / count << 16)
                            | (green / count << 8)
                            | blue / count;
                    averageHeights[outputIndex] = Math.floorDiv(height, count);
                    outputPopulated[outputIndex] = true;
                }
            }
        }

        BufferedImage image = new BufferedImage(outputWidth, outputWidth, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < outputWidth; z++) {
            for (int x = 0; x < outputWidth; x++) {
                int index = x + z * outputWidth;
                if (!outputPopulated[index]) {
                    image.setRGB(x, z, MISSING_COLOR);
                    continue;
                }

                int neighborHeight = averageHeights[index];
                int neighborCount = 0;
                if (x > 0 && outputPopulated[index - 1]) {
                    neighborHeight += averageHeights[index - 1];
                    neighborCount++;
                }
                if (z > 0 && outputPopulated[index - outputWidth]) {
                    neighborHeight += averageHeights[index - outputWidth];
                    neighborCount++;
                }
                double shade = 1.0;
                if (neighborCount > 0) {
                    int referenceHeight = Math.floorDiv(neighborHeight - averageHeights[index], neighborCount);
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
