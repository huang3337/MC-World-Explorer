package com.mcworldexplorer.experimental.v04.data;

public record VoxelChunkLoadResult(
        VoxelChunkNeighborhood neighborhood,
        long regionReadNanos,
        long parseNanos) {
    public VoxelChunkLoadResult {
        if (neighborhood == null || regionReadNanos < 0 || parseNanos < 0) {
            throw new IllegalArgumentException("invalid chunk load result");
        }
    }
}
