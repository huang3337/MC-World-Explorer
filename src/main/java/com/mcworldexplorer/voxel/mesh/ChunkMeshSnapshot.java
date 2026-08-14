package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ChunkMeshSnapshot(
        ChunkCoordinate coordinate,
        long worldOriginX,
        long worldOriginZ,
        List<MeshBatch> batches,
        MeshBounds bounds,
        int blockCount,
        int faceCount,
        int vertexCount,
        int indexCount,
        List<MeshWarning> warnings) {
    public ChunkMeshSnapshot {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(bounds, "bounds");
        batches = batches.stream()
                .sorted(Comparator.comparing(MeshBatch::layer)
                        .thenComparingInt(MeshBatch::rgb))
                .toList();
        warnings = List.copyOf(warnings);
        int actualBlocks = batches.stream().mapToInt(MeshBatch::blockCount).sum();
        int actualFaces = batches.stream().mapToInt(MeshBatch::faceCount).sum();
        int actualVertices = batches.stream().mapToInt(MeshBatch::vertexCount).sum();
        int actualIndices = batches.stream().mapToInt(batch -> batch.indices().length).sum();
        if (blockCount != actualBlocks || faceCount != actualFaces
                || vertexCount != actualVertices || indexCount != actualIndices) {
            throw new IllegalArgumentException("snapshot statistics do not match mesh batches");
        }
    }
}
