package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;

import java.util.Objects;

public record ViewerWindowContent(String title, VoxelSceneSnapshot snapshot) {
    public ViewerWindowContent {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("viewer window title must not be blank");
        }
        title = title.trim();
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
