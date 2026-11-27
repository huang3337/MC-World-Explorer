package com.mcworldexplorer.experimental.v04.render.lwjgl;

import com.mcworldexplorer.experimental.v04.mesh.MeshBatch;

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

public final class OpenGlMeshResources implements AutoCloseable {
    private final int rgb;
    private final int indexCount;
    private int vertexArray;
    private int vertexBuffer;
    private int indexBuffer;

    private OpenGlMeshResources(
            int rgb,
            int indexCount,
            int vertexArray,
            int vertexBuffer,
            int indexBuffer) {
        this.rgb = rgb;
        this.indexCount = indexCount;
        this.vertexArray = vertexArray;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
    }

    public static OpenGlMeshResources upload(MeshBatch batch) {
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
            int stride = 6 * Float.BYTES;
            glVertexAttribPointer(0, 3, org.lwjgl.opengl.GL11.GL_FLOAT, false, stride, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 3, org.lwjgl.opengl.GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
            glEnableVertexAttribArray(1);
            glBindVertexArray(0);
            return new OpenGlMeshResources(
                    batch.rgb(), batch.indices().length, vertexArray, vertexBuffer, indexBuffer);
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
        float[] vertices = new float[batch.vertexCount() * 6];
        for (int vertex = 0; vertex < batch.vertexCount(); vertex++) {
            int source = vertex * 3;
            int target = vertex * 6;
            vertices[target] = positions[source];
            vertices[target + 1] = positions[source + 1];
            vertices[target + 2] = positions[source + 2];
            vertices[target + 3] = normals[source];
            vertices[target + 4] = normals[source + 1];
            vertices[target + 5] = normals[source + 2];
        }
        return vertices;
    }

    public int rgb() {
        return rgb;
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
