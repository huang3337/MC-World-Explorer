package com.mcworldexplorer.region;

import java.io.IOException;
import java.nio.file.Path;

public final class RegionReadException extends IOException {
    public enum Reason {
        INVALID_FILE_TYPE,
        TRUNCATED_HEADER,
        LOCAL_COORDINATE_OUT_OF_RANGE,
        INVALID_LOCATION,
        TRUNCATED_CHUNK,
        INVALID_CHUNK_LENGTH,
        UNSUPPORTED_COMPRESSION,
        EXTERNAL_CHUNK_UNSUPPORTED,
        DECOMPRESSED_SIZE_LIMIT,
        DAMAGED_COMPRESSED_DATA
    }

    private final Reason reason;
    private final Path regionPath;
    private final int localChunkX;
    private final int localChunkZ;

    RegionReadException(
            Reason reason,
            Path regionPath,
            int localChunkX,
            int localChunkZ,
            String detail) {
        super(message(reason, regionPath, localChunkX, localChunkZ, detail));
        this.reason = reason;
        this.regionPath = regionPath;
        this.localChunkX = localChunkX;
        this.localChunkZ = localChunkZ;
    }

    RegionReadException(
            Reason reason,
            Path regionPath,
            int localChunkX,
            int localChunkZ,
            String detail,
            Throwable cause) {
        super(message(reason, regionPath, localChunkX, localChunkZ, detail), cause);
        this.reason = reason;
        this.regionPath = regionPath;
        this.localChunkX = localChunkX;
        this.localChunkZ = localChunkZ;
    }

    public Reason getReason() {
        return reason;
    }

    public Path getRegionPath() {
        return regionPath;
    }

    public int getLocalChunkX() {
        return localChunkX;
    }

    public int getLocalChunkZ() {
        return localChunkZ;
    }

    private static String message(
            Reason reason,
            Path regionPath,
            int localChunkX,
            int localChunkZ,
            String detail) {
        return reason + " in " + regionPath
                + " at local chunk (" + localChunkX + ", " + localChunkZ + ")"
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}
