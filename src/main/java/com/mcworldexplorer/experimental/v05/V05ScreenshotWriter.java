package com.mcworldexplorer.experimental.v05;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class V05ScreenshotWriter {
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
        if (Files.exists(normalized)) {
            throw new IOException("screenshot already exists: " + normalized);
        }
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
