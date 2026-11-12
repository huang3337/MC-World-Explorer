package com.mcworldexplorer.map;

import java.io.IOException;

/** Terminal partial result, not a transient request failure or a complete cache candidate. */
public final class MapTileIncompleteException extends IOException {
    private final MapTileGenerationResult partial;
    public MapTileIncompleteException(MapTileGenerationResult partial) {
        super(partial.unreadable().description());
        this.partial = partial;
    }
    public MapTileGenerationResult partial() { return partial; }
}
