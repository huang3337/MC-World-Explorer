package com.mcworldexplorer.experimental.v04.data;

public record VoxelLoadWarning(
        VoxelChunkNeighborhood.Direction direction,
        int chunkX,
        int chunkZ,
        String message) {
    public VoxelLoadWarning {
        if (direction == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("warning direction and message are required");
        }
    }
}
