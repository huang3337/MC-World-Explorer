package com.mcworldexplorer.map;

public record MapTileSourceState(
        int regionX,
        int regionZ,
        boolean exists,
        long size,
        long modifiedMillis) {
}
