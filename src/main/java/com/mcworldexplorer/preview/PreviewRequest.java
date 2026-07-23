package com.mcworldexplorer.preview;

import java.util.Objects;

public record PreviewRequest(
        WorldDimension dimension,
        PreviewCenter center,
        PreviewLayer layer) {
    public PreviewRequest {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(layer, "layer");
    }
}
