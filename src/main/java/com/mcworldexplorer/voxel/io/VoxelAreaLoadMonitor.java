package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;

@FunctionalInterface
public interface VoxelAreaLoadMonitor {
    VoxelAreaLoadMonitor NONE = (coordinate, status, completed, total) -> {
    };

    void onChunkCompleted(
            ChunkCoordinate coordinate,
            ChunkLoadStatus status,
            int completed,
            int total);
}
