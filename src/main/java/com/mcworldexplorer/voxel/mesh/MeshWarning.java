package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;

import java.util.Objects;

public record MeshWarning(Code code, ChunkCoordinate coordinate, String detail, int occurrences) {
    public MeshWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(coordinate, "coordinate");
        detail = detail == null ? "" : detail;
        if (occurrences < 1) {
            throw new IllegalArgumentException("occurrences must be positive");
        }
    }

    public MeshWarning(Code code, ChunkCoordinate coordinate, String detail) {
        this(code, coordinate, detail, 1);
    }

    public enum Code {
        NEIGHBOR_UNAVAILABLE,
        FALLBACK_BLOCK
    }
}
