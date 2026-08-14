package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.PreviewLayer;

import java.util.Objects;

public record ViewerMapReturnState(
        double centerX,
        double centerZ,
        double visualBlocksPerPixel,
        PreviewLayer layer) {
    public ViewerMapReturnState {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || !Double.isFinite(visualBlocksPerPixel) || visualBlocksPerPixel <= 0) {
            throw new IllegalArgumentException("map return state values must be finite and positive");
        }
        Objects.requireNonNull(layer, "layer");
    }
}
