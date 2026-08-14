package com.mcworldexplorer.voxel.mesh;

import java.util.Arrays;
import java.util.Objects;

public record MeshBatch(
        RenderLayer layer,
        int rgb,
        float alpha,
        int blockCount,
        float[] positions,
        float[] normals,
        int[] indices) {
    public MeshBatch {
        Objects.requireNonNull(layer, "layer");
        rgb &= 0xFFFFFF;
        positions = Arrays.copyOf(positions, positions.length);
        normals = Arrays.copyOf(normals, normals.length);
        indices = Arrays.copyOf(indices, indices.length);
        if (!Float.isFinite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be between 0 and 1");
        }
        if (blockCount < 0) {
            throw new IllegalArgumentException("blockCount must not be negative");
        }
        if (positions.length % 3 != 0 || normals.length != positions.length
                || indices.length % 3 != 0) {
            throw new IllegalArgumentException("mesh arrays have inconsistent lengths");
        }
        int vertexCount = positions.length / 3;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("mesh index is outside the vertex range");
            }
        }
    }

    @Override
    public float[] positions() {
        return Arrays.copyOf(positions, positions.length);
    }

    @Override
    public float[] normals() {
        return Arrays.copyOf(normals, normals.length);
    }

    @Override
    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int faceCount() {
        return indices.length / 6;
    }
}
