package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBatch;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glVertexAttribIPointer;

public final class OpenGlMeshResources implements AutoCloseable {
    static final int VERTEX_STRIDE = 13;
    private final int rgb;
    private final float alpha;
    private final boolean textured;
    private final boolean shaded;
    private final float offsetX;
    private final float offsetZ;
    private final int indexCount;
    private int vertexArray;
    private int vertexBuffer;
    private int indexBuffer;

    private OpenGlMeshResources(
            MeshBatch batch,
            float offsetX,
            float offsetZ,
            int vertexArray,
            int vertexBuffer,
            int indexBuffer) {
        this.rgb = batch.rgb();
        this.alpha = batch.alpha();
        this.textured = batch.textured();
        this.shaded = batch.shaded();
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        this.indexCount = batch.indexCount();
        this.vertexArray = vertexArray;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
    }

    public static OpenGlMeshResources upload(MeshBatch batch, float offsetX, float offsetZ) {
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetZ)) {
            throw new IllegalArgumentException("mesh offsets must be finite");
        }
        float[] vertices = interleave(batch);
        int vertexArray = 0;
        int vertexBuffer = 0;
        int indexBuffer = 0;
        try {
            vertexArray = glGenVertexArrays();
            vertexBuffer = glGenBuffers();
            indexBuffer = glGenBuffers();
            glBindVertexArray(vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, batch.indices(), GL_STATIC_DRAW);
            int stride = VERTEX_STRIDE * Float.BYTES;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
            glEnableVertexAttribArray(2);
            glVertexAttribIPointer(3, 1, GL_UNSIGNED_INT, stride, 8L * Float.BYTES);
            glEnableVertexAttribArray(3);
            glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
            glEnableVertexAttribArray(4);
            glVertexAttribPointer(5, 2, GL_FLOAT, false, stride, 10L * Float.BYTES);
            glEnableVertexAttribArray(5);
            glVertexAttribIPointer(6, 1, GL_UNSIGNED_INT, stride, 12L * Float.BYTES);
            glEnableVertexAttribArray(6);
            glBindVertexArray(0);
            return new OpenGlMeshResources(
                    batch, offsetX, offsetZ, vertexArray, vertexBuffer, indexBuffer);
        } catch (RuntimeException | Error failure) {
            glBindVertexArray(0);
            if (indexBuffer != 0) {
                glDeleteBuffers(indexBuffer);
            }
            if (vertexBuffer != 0) {
                glDeleteBuffers(vertexBuffer);
            }
            if (vertexArray != 0) {
                glDeleteVertexArrays(vertexArray);
            }
            throw failure;
        }
    }

    static float[] interleave(MeshBatch batch) {
        float[] positions = batch.positions();
        float[] normals = batch.normals();
        float[] uv = batch.textureCoordinates();
        int[] vertexRgb = batch.vertexRgb();
        float[] overlayUv = batch.overlayTextureCoordinates();
        int[] overlayRgb = batch.overlayRgb();
        boolean textured = batch.textured();
        boolean shaded = batch.shaded();
        boolean perVertexColor = vertexRgb.length != 0;
        boolean hasOverlays = overlayRgb.length != 0;
        int flags = (textured ? 1 << 24 : 0) | (shaded ? 1 << 25 : 0);
        float alpha = batch.alpha();
        int defaultRgb = batch.rgb();
        int vertexCount = batch.vertexCount();
        float[] vertices = new float[Math.multiplyExact(vertexCount, VERTEX_STRIDE)];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int source = vertex * 3;
            int target = vertex * VERTEX_STRIDE;
            vertices[target] = positions[source];
            vertices[target + 1] = positions[source + 1];
            vertices[target + 2] = positions[source + 2];
            vertices[target + 3] = normals[source];
            vertices[target + 4] = normals[source + 1];
            vertices[target + 5] = normals[source + 2];
            vertices[target + 6] = textured ? uv[vertex * 2] : 0.0f;
            vertices[target + 7] = textured ? uv[vertex * 2 + 1] : 0.0f;
            int rgb = perVertexColor ? vertexRgb[vertex] : defaultRgb;
            boolean overlay = hasOverlays && overlayRgb[vertex] >= 0;
            int material = (rgb & 0xFFFFFF) | flags | (overlay ? 1 << 26 : 0);
            vertices[target + 8] = Float.intBitsToFloat(material);
            vertices[target + 9] = alpha;
            vertices[target + 10] = overlay ? overlayUv[vertex * 2] : 0.0f;
            vertices[target + 11] = overlay ? overlayUv[vertex * 2 + 1] : 0.0f;
            vertices[target + 12] = Float.intBitsToFloat(overlay ? (overlayRgb[vertex] & 0xFFFFFF) : 0);
        }
        return vertices;
    }

    public int rgb() {
        return rgb;
    }

    public boolean textured() { return textured; }
    public boolean shaded() { return shaded; }

    public float alpha() {
        return alpha;
    }

    public float offsetX() {
        return offsetX;
    }

    public float offsetZ() {
        return offsetZ;
    }

    public int indexCount() {
        return indexCount;
    }

    public int vertexArray() {
        return vertexArray;
    }

    @Override
    public void close() {
        if (indexBuffer != 0) {
            glDeleteBuffers(indexBuffer);
            indexBuffer = 0;
        }
        if (vertexBuffer != 0) {
            glDeleteBuffers(vertexBuffer);
            vertexBuffer = 0;
        }
        if (vertexArray != 0) {
            glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
        }
    }
}
