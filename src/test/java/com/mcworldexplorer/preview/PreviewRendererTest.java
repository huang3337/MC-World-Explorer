package com.mcworldexplorer.preview;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewRendererTest {
    @Test
    void aggregatesTwoByTwoColumnsAndKeepsFixedOutputSize() {
        int[] colors = {
                0xFF0000, 0x00FF00, 0, 0,
                0x0000FF, 0xFFFFFF, 0, 0,
                0, 0, 0x777777, 0x777777,
                0, 0, 0x777777, 0x777777
        };
        int[] heights = {
                64, 64, 0, 0,
                64, 64, 0, 0,
                0, 0, 64, 64,
                0, 0, 64, 64
        };
        boolean[] populated = {
                true, true, false, false,
                true, true, false, false,
                false, false, true, true,
                false, false, true, true
        };

        BufferedImage image = new PreviewRenderer().render(4, 2, colors, heights, populated);

        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0xFF7F7F7F, image.getRGB(0, 0));
        assertEquals(0xFF20262B, image.getRGB(1, 0));
        assertEquals(0xFF777777, image.getRGB(1, 1));
    }

    @Test
    void appliesHeightShadingAndValidatesArrays() {
        int[] colors = {0x808080, 0x808080, 0x808080, 0x808080};
        int[] heights = {64, 80, 64, 80};
        boolean[] populated = {true, true, true, true};

        BufferedImage image = new PreviewRenderer().render(2, 1, colors, heights, populated);

        assertNotEquals(image.getRGB(0, 0), image.getRGB(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewRenderer().render(2, 2, new int[1], new int[1], new boolean[1]));
    }

    @Test
    void blockColorsAreStableAndMarkUnknownBlocks() {
        BlockColorPalette.BlockColor water = BlockColorPalette.resolve("minecraft:water");
        BlockColorPalette.BlockColor unknownFirst = BlockColorPalette.resolve("example:moon_dust");
        BlockColorPalette.BlockColor unknownSecond = BlockColorPalette.resolve("example:moon_dust");

        assertEquals(0x3F76E4, water.rgb());
        assertEquals(true, water.known());
        assertEquals(unknownFirst, unknownSecond);
        assertEquals(false, unknownFirst.known());
    }
}
