package com.mcworldexplorer.preview;

import java.util.ArrayList;
import java.util.List;

public record DimensionHeightRange(int minY, int maxY) {
    private static final int BAND_HEIGHT = 32;

    public DimensionHeightRange {
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must not exceed maxY");
        }
    }

    public List<PreviewLayer> layers() {
        List<PreviewLayer> layers = new ArrayList<>();
        layers.add(PreviewLayer.surfaceOverview());
        for (int bandMin = minY; bandMin <= maxY;) {
            int bandMax = (int) Math.min((long) maxY, (long) bandMin + BAND_HEIGHT - 1);
            layers.add(PreviewLayer.heightBand(bandMin, bandMax));
            if (bandMax == Integer.MAX_VALUE) {
                break;
            }
            bandMin = bandMax + 1;
        }
        return List.copyOf(layers);
    }

    public PreviewLayer bandContaining(int y) {
        int clampedY = Math.max(minY, Math.min(maxY, y));
        long offset = (long) clampedY - minY;
        int bandMin = (int) (minY + offset / BAND_HEIGHT * BAND_HEIGHT);
        int bandMax = (int) Math.min((long) maxY, (long) bandMin + BAND_HEIGHT - 1);
        return PreviewLayer.heightBand(bandMin, bandMax);
    }

    public boolean contains(PreviewLayer layer) {
        return layer.isSurfaceOverview()
                || (layer.minY() >= minY && layer.maxY() <= maxY);
    }
}
