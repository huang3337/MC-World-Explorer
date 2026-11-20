package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;

import java.util.Objects;

public record ViewerTaskResult(
        ViewerRequest request,
        VoxelSceneSnapshot snapshot,
        boolean fromCache,
        int failedChunks,
        java.util.List<ViewerCachedArea> verifiedPreviews) {
    public ViewerTaskResult(ViewerRequest request, VoxelSceneSnapshot snapshot, boolean fromCache, int failedChunks) {
        this(request, snapshot, fromCache, failedChunks, java.util.List.of());
    }

    public ViewerTaskResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshot, "snapshot");
        verifiedPreviews = java.util.List.copyOf(verifiedPreviews);
        if (failedChunks < 0 || failedChunks > request.target().width() * request.target().depth()) {
            throw new IllegalArgumentException("failed chunk count is invalid");
        }
    }

    public boolean partialSuccess() {
        return failedChunks > 0;
    }
}
