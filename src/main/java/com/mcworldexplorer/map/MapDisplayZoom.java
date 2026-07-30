package com.mcworldexplorer.map;

import java.util.Arrays;
import java.util.Objects;

public enum MapDisplayZoom {
    PIXELS_4(0.25, MapZoomLevel.BLOCKS_1),
    PIXELS_2(0.5, MapZoomLevel.BLOCKS_1),
    BLOCKS_1(1, MapZoomLevel.BLOCKS_1),
    BLOCKS_2(2, MapZoomLevel.BLOCKS_2),
    BLOCKS_4(4, MapZoomLevel.BLOCKS_4),
    BLOCKS_8(8, MapZoomLevel.BLOCKS_8),
    BLOCKS_16(16, MapZoomLevel.BLOCKS_16);

    private final double blocksPerPixel;
    private final MapZoomLevel tileZoom;

    MapDisplayZoom(double blocksPerPixel, MapZoomLevel tileZoom) {
        this.blocksPerPixel = blocksPerPixel;
        this.tileZoom = tileZoom;
    }

    public double blocksPerPixel() {
        return blocksPerPixel;
    }

    public MapZoomLevel tileZoom() {
        return tileZoom;
    }

    public MapDisplayZoom zoomIn() {
        int index = ordinal();
        return index == 0 ? this : values()[index - 1];
    }

    public MapDisplayZoom zoomOut() {
        int index = ordinal();
        return index == values().length - 1 ? this : values()[index + 1];
    }

    public static MapDisplayZoom fromTileZoom(MapZoomLevel tileZoom) {
        Objects.requireNonNull(tileZoom, "tileZoom");
        return Arrays.stream(values())
                .filter(level -> level.blocksPerPixel == tileZoom.blocksPerPixel())
                .findFirst()
                .orElseThrow();
    }

    public static MapDisplayZoom nearest(double blocksPerPixel) {
        if (!Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0) {
            throw new IllegalArgumentException("blocks-per-pixel must be positive and finite");
        }
        return Arrays.stream(values())
                .min((first, second) -> Double.compare(
                        logarithmicDistance(blocksPerPixel, first.blocksPerPixel),
                        logarithmicDistance(blocksPerPixel, second.blocksPerPixel)))
                .orElseThrow();
    }

    private static double logarithmicDistance(double value, double candidate) {
        return Math.abs(Math.log(value / candidate));
    }
}
