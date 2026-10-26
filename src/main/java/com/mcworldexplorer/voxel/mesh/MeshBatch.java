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
        int[] indices,
        float[] textureCoordinates, boolean shaded) {
    public MeshBatch(RenderLayer layer, int rgb, float alpha, int blockCount, float[] positions, float[] normals, int[] indices) {
        this(layer, rgb, alpha, blockCount, positions, normals, indices, new float[0],true);
    }
    public MeshBatch(RenderLayer layer,int rgb,float alpha,int blockCount,float[] positions,float[] normals,int[] indices,float[] uv) {
        this(layer,rgb,alpha,blockCount,positions,normals,indices,uv,true);
    }
    public MeshBatch {
        Objects.requireNonNull(layer, "layer");
        rgb &= 0xFFFFFF;
        positions = Arrays.copyOf(positions, positions.length);
        normals = Arrays.copyOf(normals, normals.length);
        indices = Arrays.copyOf(indices, indices.length);
        textureCoordinates = Arrays.copyOf(textureCoordinates, textureCoordinates.length);
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
        if (textureCoordinates.length != 0 && textureCoordinates.length != vertexCount * 2)
            throw new IllegalArgumentException("texture coordinates do not match vertices");
        for (float uv : textureCoordinates) if (!Float.isFinite(uv) || uv < 0 || uv > 1)
            throw new IllegalArgumentException("invalid texture coordinate");
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

    @Override
    public float[] textureCoordinates() { return textureCoordinates.clone(); }

    public boolean textured() { return textureCoordinates.length != 0; }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int faceCount() {
        return indices.length / 6;
    }
}
