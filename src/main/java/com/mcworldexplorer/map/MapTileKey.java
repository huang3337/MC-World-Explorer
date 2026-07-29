package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;

import java.util.Objects;

public record MapTileKey(
        String worldId,
        String dimensionId,
        PreviewLayer layer,
        MapZoomLevel zoom,
        long tileX,
        long tileZ,
        String renderVersion) {

    public MapTileKey {
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("worldId must not be blank");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(zoom, "zoom");
        if (renderVersion == null || renderVersion.isBlank()) {
            throw new IllegalArgumentException("renderVersion must not be blank");
        }
    }

    public MapTileBounds bounds() {
        return MapTileBounds.of(tileX, tileZ, zoom);
    }

    public static long tileCoordinate(long blockCoordinate, MapZoomLevel zoom) {
        if (zoom == null) {
            throw new IllegalArgumentException("zoom must not be null");
        }
        return Math.floorDiv(blockCoordinate, zoom.tileBlockSize());
    }
}
