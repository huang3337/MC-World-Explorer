package com.mcworldexplorer.preview;

import java.nio.file.Path;
import java.util.Objects;

public record PreviewCacheResult(Path imagePath, Path metadataPath) {
    public PreviewCacheResult {
        Objects.requireNonNull(imagePath, "imagePath");
        Objects.requireNonNull(metadataPath, "metadataPath");
    }
}
