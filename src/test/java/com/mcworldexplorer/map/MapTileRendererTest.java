package com.mcworldexplorer.map;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapTileRendererTest {
    @Test
    void averagesOnlyPopulatedColumnsAtConfirmedScale() {
        MapTileRenderer renderer = new MapTileRenderer();
        MapTileBounds bounds = MapTileBounds.of(0, 0, MapZoomLevel.BLOCKS_2);

        renderer.accept(bounds, MapZoomLevel.BLOCKS_2, 0, 0, 0xFF0000, 64);
        renderer.accept(bounds, MapZoomLevel.BLOCKS_2, 1, 0, 0x00FF00, 64);
        renderer.accept(bounds, MapZoomLevel.BLOCKS_2, 0, 1, 0x0000FF, 64);
        renderer.accept(bounds, MapZoomLevel.BLOCKS_2, 1, 1, 0xFFFFFF, 64);

        BufferedImage image = renderer.render();

        assertEquals(0xFF7F7F7F, image.getRGB(0, 0));
        assertEquals(0xFF20262B, image.getRGB(1, 0));
    }

    @Test
    void ignoresColumnsOutsideTileBounds() {
        MapTileRenderer renderer = new MapTileRenderer();
        MapTileBounds bounds = MapTileBounds.of(-1, -1, MapZoomLevel.BLOCKS_1);

        renderer.accept(bounds, MapZoomLevel.BLOCKS_1, 0, 0, 0xFFFFFF, 10);

        assertEquals(0xFF20262B, renderer.render().getRGB(255, 255));
    }

    @Test
    void partialRenderKeepsUnpopulatedPixelsTransparent() {
        MapTileRenderer renderer = new MapTileRenderer();
        MapTileBounds bounds = MapTileBounds.of(0, 0, MapZoomLevel.BLOCKS_1);
        renderer.accept(bounds, MapZoomLevel.BLOCKS_1, 0, 0, 0x44AA66, 64);

        BufferedImage partial = renderer.renderPartial();

        assertEquals(0xFF44AA66, partial.getRGB(0, 0));
        assertEquals(0, partial.getRGB(1, 0));
        assertEquals(0xFF20262B, renderer.render().getRGB(1, 0));
    }
}
