package com.mcworldexplorer.map;

public record MapTileBounds(long minX, long minZ, long maxXExclusive, long maxZExclusive) {
    public static final int TILE_PIXELS = 256;

    public MapTileBounds {
        if (minX >= maxXExclusive || minZ >= maxZExclusive) {
            throw new IllegalArgumentException("tile bounds must contain a positive area");
        }
    }

    public static MapTileBounds of(long tileX, long tileZ, MapZoomLevel zoom) {
        if (zoom == null) {
            throw new IllegalArgumentException("zoom must not be null");
        }
        long size = zoom.tileBlockSize();
        long minX = Math.multiplyExact(tileX, size);
        long minZ = Math.multiplyExact(tileZ, size);
        return new MapTileBounds(
                minX,
                minZ,
                Math.addExact(minX, size),
                Math.addExact(minZ, size));
    }

    public long blockWidth() {
        return maxXExclusive - minX;
    }

    public long blockHeight() {
        return maxZExclusive - minZ;
    }
}
