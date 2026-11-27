package com.mcworldexplorer.map;

/** Full precision metadata and header digest, not a body content hash. */
public record MapTileSourceState(int regionX, int regionZ, boolean exists, String signature, String failure) {
    public MapTileSourceState { failure = failure == null ? "" : failure; }
    public MapTileSourceState(int regionX, int regionZ, boolean exists, String signature) {
        this(regionX, regionZ, exists, signature, "");
    }
    public boolean unreadable() { return failure != null && !failure.isEmpty(); }
}
