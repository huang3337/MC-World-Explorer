package com.mcworldexplorer.map;

import java.awt.image.BufferedImage;
import java.util.List;

public record MapTileGenerationResult(
        BufferedImage image,
        List<MapMarker> markers,
        int totalChunks,
        int sampledChunks,
        int missingChunks,
        int failedChunks,
        int populatedColumns,
        int unknownBlockColumns) {

    public MapTileGenerationResult {
        if (image == null || markers == null) {
            throw new IllegalArgumentException("image and markers must not be null");
        }
        markers = List.copyOf(markers);
    }
}
