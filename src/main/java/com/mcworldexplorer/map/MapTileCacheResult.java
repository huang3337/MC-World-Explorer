package com.mcworldexplorer.map;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
public record MapTileCacheResult(Path imagePath, Path metadataPath, BufferedImage image,
                                 List<MapMarker> markers, List<MapTileSourceState> sources, String identity, SaveState saveState) {
    public enum SaveState { SAVED, CAPACITY_LIMIT, IO_FAILED, ENCODING_LIMIT }
    public MapTileCacheResult(Path imagePath, Path metadataPath, BufferedImage image, List<MapMarker> markers, List<MapTileSourceState> sources, String identity) { this(imagePath, metadataPath, image, markers, sources, identity, SaveState.SAVED); }
    public MapTileCacheResult { markers = List.copyOf(markers); sources = List.copyOf(sources); }
    public MapTileCacheResult(Path imagePath, Path metadataPath, BufferedImage image, List<MapMarker> markers) {
        this(imagePath, metadataPath, image, markers, List.of(), "");
    }
}
