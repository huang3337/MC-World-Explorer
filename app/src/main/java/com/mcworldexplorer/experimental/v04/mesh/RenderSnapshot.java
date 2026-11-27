package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelLoadWarning;

import java.util.Comparator;
import java.util.List;

public record RenderSnapshot(
        List<MeshBatch> batches,
        MeshBounds bounds,
        int blockCount,
        int solidBlockCount,
        int fluidBlockCount,
        int faceCount,
        int fluidFaceCount,
        int vertexCount,
        int indexCount,
        List<VoxelLoadWarning> warnings) {
    public RenderSnapshot {
        batches = batches.stream()
                .sorted(Comparator.comparingInt(MeshBatch::rgb))
                .toList();
        warnings = List.copyOf(warnings);
        int actualFaces = batches.stream().mapToInt(MeshBatch::faceCount).sum();
        int actualVertices = batches.stream().mapToInt(MeshBatch::vertexCount).sum();
        int actualIndices = batches.stream().mapToInt(batch -> batch.indices().length).sum();
        if (blockCount < 0 || solidBlockCount < 0 || fluidBlockCount < 0
                || blockCount != solidBlockCount + fluidBlockCount
                || fluidFaceCount < 0 || fluidFaceCount > faceCount || faceCount != actualFaces
                || vertexCount != actualVertices || indexCount != actualIndices) {
            throw new IllegalArgumentException("snapshot statistics do not match mesh batches");
        }
    }

    public boolean boundaryComplete() {
        return warnings.isEmpty();
    }
}
