package com.mcworldexplorer.viewer;

import java.util.Objects;
import java.util.Optional;

/** Small cache metadata; does not retain the voxel data or mesh. */
public record ViewerCachedArea(ViewerAreaKey key, Optional<ViewerAreaThumbnail> thumbnail, int failedChunks) {
    public ViewerCachedArea {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(thumbnail, "thumbnail");
        if (failedChunks < 0 || failedChunks > key.target().width() * key.target().depth()) {
            throw new IllegalArgumentException("invalid failed chunk count");
        }
    }
}
