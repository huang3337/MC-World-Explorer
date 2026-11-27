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
        float[] textureCoordinates,
        boolean shaded,
        int[] vertexRgb,
        float[] overlayTextureCoordinates,
        int[] overlayRgb) {
    public MeshBatch(RenderLayer layer, int rgb, float alpha, int blockCount, float[] positions, float[] normals, int[] indices) {
        this(layer, rgb, alpha, blockCount, positions, normals, indices, new float[0], true, new int[0], new float[0], new int[0]);
    }
    public MeshBatch(RenderLayer layer, int rgb, float alpha, int blockCount, float[] positions, float[] normals, int[] indices, float[] uv) {
        this(layer, rgb, alpha, blockCount, positions, normals, indices, uv, true, new int[0], new float[0], new int[0]);
    }
    public MeshBatch(RenderLayer layer, int rgb, float alpha, int blockCount, float[] positions, float[] normals, int[] indices, float[] textureCoordinates, boolean shaded) {
        this(layer, rgb, alpha, blockCount, positions, normals, indices, textureCoordinates, shaded, new int[0], new float[0], new int[0]);
    }
    public MeshBatch {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(normals, "normals");
        Objects.requireNonNull(indices, "indices");
        Objects.requireNonNull(textureCoordinates, "textureCoordinates");
        Objects.requireNonNull(vertexRgb, "vertexRgb");
        Objects.requireNonNull(overlayTextureCoordinates, "overlayTextureCoordinates");
        Objects.requireNonNull(overlayRgb, "overlayRgb");
        rgb &= 0xFFFFFF;
        positions = Arrays.copyOf(positions, positions.length);
        normals = Arrays.copyOf(normals, normals.length);
        indices = Arrays.copyOf(indices, indices.length);
        textureCoordinates = Arrays.copyOf(textureCoordinates, textureCoordinates.length);
        vertexRgb = Arrays.copyOf(vertexRgb, vertexRgb.length);
        overlayTextureCoordinates = Arrays.copyOf(overlayTextureCoordinates, overlayTextureCoordinates.length);
        overlayRgb = Arrays.copyOf(overlayRgb, overlayRgb.length);
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
        if (vertexRgb.length != 0 && vertexRgb.length != vertexCount) {
            throw new IllegalArgumentException("vertex colors do not match vertices");
        }
        for (int i = 0; i < vertexRgb.length; i++) {
            vertexRgb[i] &= 0xFFFFFF;
        }
        if (overlayTextureCoordinates.length == 0) {
            if (overlayRgb.length != 0) {
                throw new IllegalArgumentException("overlay colors require overlay texture coordinates");
            }
        } else {
            if (textureCoordinates.length == 0) {
                throw new IllegalArgumentException("overlay texture coordinates require base texture coordinates");
            }
            if (overlayTextureCoordinates.length != vertexCount * 2 || overlayRgb.length != vertexCount) {
                throw new IllegalArgumentException("overlay arrays do not match vertices");
            }
            for (float uv : overlayTextureCoordinates) if (!Float.isFinite(uv) || uv < 0 || uv > 1)
                throw new IllegalArgumentException("invalid overlay texture coordinate");
            for (int i = 0; i < overlayRgb.length; i++) {
                overlayRgb[i] = overlayRgb[i] < 0 ? -1 : (overlayRgb[i] & 0xFFFFFF);
            }
        }
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

    @Override
    public int[] vertexRgb() { return vertexRgb.clone(); }

    @Override
    public float[] overlayTextureCoordinates() { return overlayTextureCoordinates.clone(); }

    @Override
    public int[] overlayRgb() { return overlayRgb.clone(); }

    public boolean textured() { return textureCoordinates.length != 0; }

    public boolean hasVertexColors() { return vertexRgb.length != 0; }

    public boolean hasOverlays() { return overlayTextureCoordinates.length != 0; }

    public int vertexColor(int vertex) {
        Objects.checkIndex(vertex, vertexCount());
        return vertexRgb.length == 0 ? rgb : vertexRgb[vertex];
    }

    public boolean hasOverlay(int vertex) {
        Objects.checkIndex(vertex, vertexCount());
        return overlayRgb.length != 0 && overlayRgb[vertex] >= 0;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int faceCount() {
        return indices.length / 6;
    }

    public int indexCount() {
        return indices.length;
    }
}
