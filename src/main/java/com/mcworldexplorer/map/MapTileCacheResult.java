package com.mcworldexplorer.map;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

public record MapTileCacheResult(
        Path imagePath,
        Path metadataPath,
        BufferedImage image,
        List<MapMarker> markers) {

    public MapTileCacheResult {
        markers = List.copyOf(markers);
    }
}
