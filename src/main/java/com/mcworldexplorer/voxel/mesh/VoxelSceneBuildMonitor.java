package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;

@FunctionalInterface
public interface VoxelSceneBuildMonitor {
    VoxelSceneBuildMonitor NONE = (coordinate, status, completed, total) -> {
    };

    void onChunkCompleted(
            ChunkCoordinate coordinate,
            ChunkLoadStatus status,
            int completed,
            int total);
}
