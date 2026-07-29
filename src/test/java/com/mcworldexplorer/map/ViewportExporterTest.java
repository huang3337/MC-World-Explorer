package com.mcworldexplorer.map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ViewportExporterTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(HOME_PROPERTY);

    @AfterEach
    void restoreHome() {
        if (originalHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void exportsViewportPixelsAndRemovesTemporarySource() throws IOException {
        Path app = tempDir.resolve("app");
        Path world = Files.createDirectory(tempDir.resolve("world"));
        System.setProperty(HOME_PROPERTY, app.toString());
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 1, 0xff44aa66);

        Path exported = new ViewportExporter().exportToDefault(source, "Map", world);

        BufferedImage actual = ImageIO.read(exported.toFile());
        assertEquals(3, actual.getWidth());
        assertEquals(2, actual.getHeight());
        assertEquals(0xff44aa66, actual.getRGB(1, 1));
        Path temporaryDirectory = app.resolve("cache/export-temp");
        if (Files.isDirectory(temporaryDirectory)) {
            try (var files = Files.list(temporaryDirectory)) {
                assertFalse(files.findAny().isPresent());
            }
        }
    }
}
