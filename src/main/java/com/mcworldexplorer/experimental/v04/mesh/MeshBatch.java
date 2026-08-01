package com.mcworldexplorer.experimental.v04.mesh;

import java.util.Arrays;

public record MeshBatch(int rgb, float[] positions, float[] normals, int[] indices) {
    public MeshBatch {
        positions = Arrays.copyOf(positions, positions.length);
        normals = Arrays.copyOf(normals, normals.length);
        indices = Arrays.copyOf(indices, indices.length);
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
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("mesh position must be finite");
            }
        }
        for (float value : normals) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("mesh normal must be finite");
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
