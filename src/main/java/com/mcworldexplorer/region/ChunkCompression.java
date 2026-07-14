package com.mcworldexplorer.region;

import java.util.Arrays;
import java.util.Optional;

public enum ChunkCompression {
    GZIP(1, true),
    ZLIB(2, true),
    UNCOMPRESSED(3, true),
    LZ4(4, false);

    private final int id;
    private final boolean supported;

    ChunkCompression(int id, boolean supported) {
        this.id = id;
        this.supported = supported;
    }

    public int id() {
        return id;
    }

    public boolean isSupported() {
        return supported;
    }

    static Optional<ChunkCompression> fromId(int id) {
        return Arrays.stream(values()).filter(value -> value.id == id).findFirst();
    }
}
