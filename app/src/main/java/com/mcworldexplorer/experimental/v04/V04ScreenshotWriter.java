package com.mcworldexplorer.experimental.v04;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class V04ScreenshotWriter {
    public void write(Path target, int width, int height, int[] argb, boolean bottomUp)
            throws IOException {
        if (width < 1 || height < 1 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("invalid screenshot dimensions or pixel count");
        }
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("screenshot path has no parent: " + normalized);
        }
        Files.createDirectories(parent);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int sourceY = bottomUp ? height - 1 - y : y;
            image.setRGB(0, y, width, 1, argb, sourceY * width, width);
        }
        if (!ImageIO.write(image, "png", normalized.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
    }
}
