package com.mcworldexplorer.voxel.resource;

import java.io.IOException;

public final class ResourceAccessException extends IOException {
    public enum Reason {
        SOURCE_MISSING, INVALID_ARCHIVE, INVALID_METADATA, VERSION_MISMATCH,
        LIMIT_EXCEEDED, SOURCE_CHANGED, CLOSED, IO_FAILURE
    }

    private final Reason reason;

    public ResourceAccessException(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public ResourceAccessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() { return reason; }
}
