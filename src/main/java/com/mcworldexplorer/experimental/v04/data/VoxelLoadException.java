package com.mcworldexplorer.experimental.v04.data;

public final class VoxelLoadException extends Exception {
    private final Reason reason;
    private final int chunkX;
    private final int chunkZ;

    public VoxelLoadException(Reason reason, int chunkX, int chunkZ, String message) {
        super(message);
        this.reason = reason;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public VoxelLoadException(Reason reason, int chunkX, int chunkZ, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public Reason reason() {
        return reason;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public enum Reason {
        DIMENSION_NOT_FOUND,
        REGION_DIRECTORY_INVALID,
        TARGET_CHUNK_MISSING,
        TARGET_CHUNK_UNREADABLE
    }
}
