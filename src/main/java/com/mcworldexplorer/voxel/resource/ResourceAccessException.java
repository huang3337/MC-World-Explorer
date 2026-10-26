package com.mcworldexplorer.voxel.resource;

import java.io.IOException;

public final class ResourceAccessException extends IOException {
    public enum Reason {
        SOURCE_MISSING, INVALID_ARCHIVE, INVALID_METADATA, VERSION_MISMATCH,
        LIMIT_EXCEEDED, SOURCE_CHANGED, CLOSED, IO_FAILURE
    }

    public enum Scope { SOURCE, RESOURCE }
    private final Scope scope;
    private final Reason reason;

    public ResourceAccessException(Reason reason, String message) {
        this(reason,message,Scope.SOURCE);
    }
    public ResourceAccessException(Reason reason,String message,Scope scope) {
        super(message);this.scope=java.util.Objects.requireNonNull(scope);
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public ResourceAccessException(Reason reason, String message, Throwable cause) {
        super(message, cause);this.scope=Scope.SOURCE;
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Scope scope(){return scope;}
    public Reason reason() { return reason; }
}
