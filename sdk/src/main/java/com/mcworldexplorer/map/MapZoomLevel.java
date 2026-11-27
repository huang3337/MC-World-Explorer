package com.mcworldexplorer.map;

import java.util.Arrays;

public enum MapZoomLevel {
    BLOCKS_1(1),
    BLOCKS_2(2),
    BLOCKS_4(4),
    BLOCKS_8(8),
    BLOCKS_16(16);

    private final int blocksPerPixel;

    MapZoomLevel(int blocksPerPixel) {
        this.blocksPerPixel = blocksPerPixel;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }

    public long tileBlockSize() {
        return (long) MapTileBounds.TILE_PIXELS * blocksPerPixel;
    }

    public MapZoomLevel zoomIn() {
        int index = ordinal();
        return index == 0 ? this : values()[index - 1];
    }

    public MapZoomLevel zoomOut() {
        int index = ordinal();
        return index == values().length - 1 ? this : values()[index + 1];
    }

    public static MapZoomLevel fromBlocksPerPixel(int blocksPerPixel) {
        return Arrays.stream(values())
                .filter(level -> level.blocksPerPixel == blocksPerPixel)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported blocks-per-pixel: " + blocksPerPixel));
    }

    public static MapZoomLevel nearest(double blocksPerPixel) {
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
