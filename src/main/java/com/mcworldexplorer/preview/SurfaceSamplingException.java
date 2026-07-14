package com.mcworldexplorer.preview;

import java.io.IOException;

public final class SurfaceSamplingException extends IOException {
    public enum Reason {
        INVALID_NBT,
        UNSUPPORTED_CHUNK_LAYOUT,
        INVALID_SECTION,
        INVALID_PALETTE,
        INVALID_BLOCK_STATE_STORAGE,
        PALETTE_INDEX_OUT_OF_RANGE
    }

    private final Reason reason;

    SurfaceSamplingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    SurfaceSamplingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
