package com.mcworldexplorer.voxel.mesh;

import java.util.Optional;
import com.mcworldexplorer.voxel.resource.TextureAtlas;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record VoxelSceneSnapshot(
        long anchorWorldX,
        long anchorWorldZ,
        List<ChunkMeshSnapshot> chunks,
        MeshBounds bounds,
        int blockCount,
        int faceCount,
        int vertexCount,
        int indexCount,
        List<MeshWarning> warnings,
        Optional<TextureAtlas> atlas) {
    public VoxelSceneSnapshot(long anchorWorldX, long anchorWorldZ, List<ChunkMeshSnapshot> chunks,
                              MeshBounds bounds, int blockCount, int faceCount, int vertexCount,
                              int indexCount, List<MeshWarning> warnings) {
        this(anchorWorldX, anchorWorldZ, chunks, bounds, blockCount, faceCount, vertexCount, indexCount, warnings, Optional.empty());
    }
    public VoxelSceneSnapshot {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(atlas, "atlas");
        if (atlas.isEmpty() && chunks.stream().flatMap(c -> c.batches().stream()).anyMatch(MeshBatch::textured))
            throw new IllegalArgumentException("textured scene requires an atlas");
        chunks = chunks.stream()
                .sorted(Comparator.comparing(ChunkMeshSnapshot::coordinate))
                .toList();
        warnings = List.copyOf(warnings);
        int actualBlocks = chunks.stream().mapToInt(ChunkMeshSnapshot::blockCount).sum();
        int actualFaces = chunks.stream().mapToInt(ChunkMeshSnapshot::faceCount).sum();
        int actualVertices = chunks.stream().mapToInt(ChunkMeshSnapshot::vertexCount).sum();
        int actualIndices = chunks.stream().mapToInt(ChunkMeshSnapshot::indexCount).sum();
        if (blockCount != actualBlocks || faceCount != actualFaces
                || vertexCount != actualVertices || indexCount != actualIndices) {
            throw new IllegalArgumentException("scene statistics do not match chunk meshes");
        }
    }
}
