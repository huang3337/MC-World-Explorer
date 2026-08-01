package com.mcworldexplorer.experimental.v04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V04ScreenshotWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesAndVerticallyFlipsArgbPixels() throws Exception {
        Path target = tempDir.resolve("shots").resolve("trial.png");
        int red = 0xFFFF0000;
        int blue = 0xFF0000FF;

        new V04ScreenshotWriter().write(target, 1, 2, new int[]{red, blue}, true);

        BufferedImage image = ImageIO.read(target.toFile());
        assertEquals(blue, image.getRGB(0, 0));
        assertEquals(red, image.getRGB(0, 1));
    }

    @Test
    void rejectsMismatchedPixelCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new V04ScreenshotWriter().write(
                        tempDir.resolve("bad.png"), 2, 2, new int[3], false));
    }
}
