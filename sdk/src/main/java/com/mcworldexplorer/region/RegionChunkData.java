package com.mcworldexplorer.region;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

public final class RegionChunkData {
    private final int localChunkX;
    private final int localChunkZ;
    private final long timestamp;
    private final ChunkCompression compression;
    private final byte[] nbtData;

    RegionChunkData(
            int localChunkX,
            int localChunkZ,
            long timestamp,
            ChunkCompression compression,
            byte[] nbtData) {
        this.localChunkX = localChunkX;
        this.localChunkZ = localChunkZ;
        this.timestamp = timestamp;
        this.compression = Objects.requireNonNull(compression, "compression");
        this.nbtData = Objects.requireNonNull(nbtData, "nbtData");
    }

    public int getLocalChunkX() {
        return localChunkX;
    }

    public int getLocalChunkZ() {
        return localChunkZ;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public ChunkCompression getCompression() {
        return compression;
    }

    public int getNbtDataLength() {
        return nbtData.length;
    }

    public InputStream openNbtStream() {
        return new ByteArrayInputStream(nbtData);
    }
}
