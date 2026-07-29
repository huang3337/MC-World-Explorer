package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewExporter;
import com.mcworldexplorer.preview.PreviewExporter.ExportException;
import com.mcworldexplorer.storage.PortablePaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ViewportExporter {
    private final PreviewExporter previewExporter = new PreviewExporter();

    public Path exportToDefault(
            BufferedImage image,
            String worldName,
            Path worldDirectory) throws IOException {
        Path temporary = writeTemporary(image);
        try {
            return previewExporter.exportToDefault(temporary, worldName, worldDirectory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path exportToFile(
            BufferedImage image,
            Path target,
            Path worldDirectory) throws IOException {
        Path temporary = writeTemporary(image);
        try {
            return previewExporter.exportToFile(temporary, target, worldDirectory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public String suggestedFileName(String worldName) {
        return previewExporter.suggestedFileName(worldName);
    }

    private static Path writeTemporary(BufferedImage image) throws IOException {
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IllegalArgumentException("viewport image must not be empty");
        }
        Path directory = PortablePaths.cacheDirectory().resolve("export-temp");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".viewport-", ".png");
        boolean written = false;
        try {
            written = ImageIO.write(image, "png", temporary.toFile());
            if (!written) {
                throw new IOException("PNG writer is unavailable");
            }
            return temporary;
        } finally {
            if (!written) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
