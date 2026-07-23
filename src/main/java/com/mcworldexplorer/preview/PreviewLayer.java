package com.mcworldexplorer.preview;

import java.util.Objects;

public record PreviewLayer(PreviewLayerType type, int minY, int maxY) {
    private static final PreviewLayer SURFACE = new PreviewLayer(
            PreviewLayerType.SURFACE_OVERVIEW,
            0,
            0);

    public PreviewLayer {
        Objects.requireNonNull(type, "type");
        if (type == PreviewLayerType.SURFACE_OVERVIEW) {
            if (minY != 0 || maxY != 0) {
                throw new IllegalArgumentException("surface overview must use the canonical 0..0 range");
            }
        } else if (minY > maxY || (long) maxY - minY + 1 > 32) {
            throw new IllegalArgumentException("height band must contain between 1 and 32 blocks");
        }
    }

    public static PreviewLayer surfaceOverview() {
        return SURFACE;
    }

    public static PreviewLayer heightBand(int minY, int maxY) {
        return new PreviewLayer(PreviewLayerType.HEIGHT_BAND, minY, maxY);
    }

    public boolean isSurfaceOverview() {
        return type == PreviewLayerType.SURFACE_OVERVIEW;
    }

    public String cacheKey() {
        return isSurfaceOverview() ? "surface-overview" : "y-" + minY + "-" + maxY;
    }

    @Override
    public String toString() {
        return isSurfaceOverview() ? "地表总览" : "Y " + minY + " - " + maxY;
    }
}
