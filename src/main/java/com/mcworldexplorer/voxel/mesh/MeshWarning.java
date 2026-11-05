package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.resource.BlockResourceDiagnostic;

import java.util.Objects;
import java.util.Optional;

public record MeshWarning(Code code, ChunkCoordinate coordinate, String detail, int occurrences,
                          Optional<BlockResourceDiagnostic> diagnostic) {
    public MeshWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(diagnostic,"diagnostic");
        detail = detail == null ? "" : detail;
        if (occurrences < 1) {
            throw new IllegalArgumentException("occurrences must be positive");
        }
    }

    public MeshWarning(Code code,ChunkCoordinate coordinate,String detail,int occurrences){this(code,coordinate,detail,occurrences,Optional.empty());}

    public MeshWarning(Code code, ChunkCoordinate coordinate, String detail) {
        this(code, coordinate, detail, 1);
    }

    public enum Code {
        NEIGHBOR_UNAVAILABLE,
        FALLBACK_BLOCK,
        TEXTURE_FALLBACK,
        MODEL_APPROXIMATION,
        RESOURCE_CONTEXT,
        RESOURCE_ORIGIN,
        RESOURCE_WARNING
    }
}
