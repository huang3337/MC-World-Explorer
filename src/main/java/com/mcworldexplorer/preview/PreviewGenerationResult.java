package com.mcworldexplorer.preview;

import java.awt.image.BufferedImage;
import java.util.Objects;

public record PreviewGenerationResult(
        BufferedImage image,
        PreviewCenter center,
        int totalChunks,
        int sampledChunks,
        int missingChunks,
        int failedChunks,
        int populatedColumns,
        int unknownBlockColumns) {
    public PreviewGenerationResult {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(center, "center");
    }
}
