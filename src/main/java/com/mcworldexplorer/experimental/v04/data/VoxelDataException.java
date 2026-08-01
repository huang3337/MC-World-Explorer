package com.mcworldexplorer.experimental.v04.data;

public final class VoxelDataException extends Exception {
    private final Reason reason;
    private final int sectionY;

    VoxelDataException(Reason reason, int sectionY, String message) {
        super(message);
        this.reason = reason;
        this.sectionY = sectionY;
    }

    VoxelDataException(Reason reason, int sectionY, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.sectionY = sectionY;
    }

    public Reason reason() {
        return reason;
    }

    public int sectionY() {
        return sectionY;
    }

    public enum Reason {
        INVALID_NBT,
        CHUNK_COORDINATE_MISMATCH,
        UNSUPPORTED_CHUNK_LAYOUT,
        INVALID_SECTION,
        INVALID_PALETTE,
        INVALID_PALETTE_PROPERTY,
        INVALID_BLOCK_STATE_STORAGE,
        PALETTE_INDEX_OUT_OF_RANGE
    }
}
