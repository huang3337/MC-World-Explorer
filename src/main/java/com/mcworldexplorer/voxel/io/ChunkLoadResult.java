package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.VoxelChunk;

import java.util.Objects;
import java.util.Optional;

public record ChunkLoadResult(
        ChunkCoordinate coordinate,
        ChunkLoadStatus status,
        VoxelChunk chunk,
        String detail,
        long regionReadNanos,
        long parseNanos) {
    public ChunkLoadResult {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
        if ((status == ChunkLoadStatus.SUCCESS) != (chunk != null)) {
            throw new IllegalArgumentException("only SUCCESS results may contain a chunk");
        }
        if (regionReadNanos < 0 || parseNanos < 0) {
            throw new IllegalArgumentException("timings must not be negative");
        }
    }

    public static ChunkLoadResult success(
            ChunkCoordinate coordinate,
            VoxelChunk chunk,
            long regionReadNanos,
            long parseNanos) {
        return new ChunkLoadResult(
                coordinate,
                ChunkLoadStatus.SUCCESS,
                chunk,
                "",
                regionReadNanos,
                parseNanos);
    }

    public static ChunkLoadResult withoutChunk(
            ChunkCoordinate coordinate,
            ChunkLoadStatus status,
            String detail,
            long regionReadNanos,
            long parseNanos) {
        if (status == ChunkLoadStatus.SUCCESS) {
            throw new IllegalArgumentException("success requires a chunk");
        }
        return new ChunkLoadResult(
                coordinate, status, null, detail, regionReadNanos, parseNanos);
    }

    public Optional<VoxelChunk> optionalChunk() {
        return Optional.ofNullable(chunk);
    }
}
