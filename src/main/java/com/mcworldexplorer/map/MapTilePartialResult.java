package com.mcworldexplorer.map;

import java.awt.image.BufferedImage;
import java.util.List;

public record MapTilePartialResult(
        BufferedImage image,
        List<MapMarker> markers,
        int completedChunks,
        int totalChunks) {

    public MapTilePartialResult {
        if (image == null || markers == null) {
            throw new IllegalArgumentException("partial image and markers must not be null");
        }
        if (completedChunks < 0 || totalChunks < 0 || completedChunks > totalChunks) {
            throw new IllegalArgumentException("partial progress is invalid");
        }
        markers = List.copyOf(markers);
    }
}
