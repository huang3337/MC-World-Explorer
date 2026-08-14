package com.mcworldexplorer.experimental.v05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V05ScreenshotWriterTest {
    @TempDir
    Path temporary;

    @Test
    void writesBottomUpPngAndProtectsExistingFile() throws Exception {
        Path target = temporary.resolve("nested").resolve("scene.png");
        new V05ScreenshotWriter().write(
                target,
                1,
                2,
                new int[]{0xFFFF0000, 0xFF0000FF},
                true);

        var image = ImageIO.read(target.toFile());
        assertEquals(0xFF0000FF, image.getRGB(0, 0));
        assertEquals(0xFFFF0000, image.getRGB(0, 1));
        assertThrows(IOException.class, () -> new V05ScreenshotWriter().write(
                target, 1, 1, new int[]{0xFFFFFFFF}, false));
    }
}
