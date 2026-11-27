package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBatch;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/** A single vertex stream lets water and other translucent materials interleave across chunks. */
final class TranslucentFaces implements AutoCloseable {
    record Part(MeshBatch batch, int offsetX, int offsetZ) {}

    private static final int STRIDE = OpenGlMeshResources.VERTEX_STRIDE;
    private static final class Face {
        final int first, count, cellX, cellY, cellZ;
        final float x, y, z;
        double shell, depth;

        Face(int first, int count, float x, float y, float z, float nx, float ny, float nz) {
            this.first = first;
            this.count = count;
            this.x = x;
            this.y = y;
            this.z = z;
            cellX = cell(x, nx);
            cellY = cell(y, ny);
            cellZ = cell(z, nz);
        }

        // A face on a voxel boundary belongs to the cell behind its outward normal.
        private static int cell(float position, float normal) {
            int floor = (int) Math.floor(position);
            return normal > 0 && position == floor ? floor - 1 : floor;
        }
    }

    private static final Comparator<Face> FAR_FIRST = Comparator
            .comparingDouble((Face f) -> f.shell).thenComparingDouble(f -> f.depth)
            .reversed().thenComparingInt(f -> f.first);
    private final int[] indices;
    private final int[] ordered;
    private final Face[] faces;
    private final Vector3f previousEye = new Vector3f(Float.NaN);
    private final Vector3f eye = new Vector3f();
    private boolean previousPerspective;
    private int vao, vbo, ebo;

    TranslucentFaces(List<Part> parts) {
        int vertexCount = 0, indexCount = 0;
        for (var part : parts) {
            vertexCount = Math.addExact(vertexCount, part.batch.vertexCount());
            indexCount = Math.addExact(indexCount, part.batch.indexCount());
        }
        float[] vertices = new float[Math.multiplyExact(vertexCount, STRIDE)];
        indices = new int[indexCount];
        ordered = new int[indexCount];
        var primitives = new ArrayList<Face>();
        int base = 0, first = 0;
        for (var part : parts) {
            var batch = part.batch;
            float[] source = OpenGlMeshResources.interleave(batch);
            for (int i = 0; i < batch.vertexCount(); i++) {
                int p = (base + i) * STRIDE;
                System.arraycopy(source, i * STRIDE, vertices, p, STRIDE);
                vertices[p] += part.offsetX;
                vertices[p + 2] += part.offsetZ;
            }
            int[] local = batch.indices();
            for (int i = 0; i < local.length; i++) indices[first + i] = base + local[i];
            for (int i = first; i < first + local.length;) {
                boolean quad = i + 5 < first + local.length
                        && indices[i] == indices[i + 3] && indices[i + 2] == indices[i + 4];
                int n = quad ? 4 : 3;
                float x = 0, y = 0, z = 0;
                for (int j = 0; j < n; j++) {
                    int p = indices[i + (j == 3 ? 5 : j)] * STRIDE;
                    x += vertices[p]; y += vertices[p + 1]; z += vertices[p + 2];
                }
                int p = indices[i] * STRIDE;
                int count = quad ? 6 : 3;
                primitives.add(new Face(i, count, x / n, y / n, z / n,
                        vertices[p + 3], vertices[p + 4], vertices[p + 5]));
                i += count;
            }
            base += batch.vertexCount();
            first += local.length;
        }
        faces = primitives.toArray(Face[]::new);
        if (indexCount == 0) return;
        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            ebo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            attribute(0, 3, 0);
            attribute(1, 3, 3);
            attribute(2, 2, 6);
            glVertexAttribIPointer(3, 1, GL_UNSIGNED_INT, STRIDE * Float.BYTES, 8L * Float.BYTES);
            glEnableVertexAttribArray(3);
            attribute(4, 1, 9);
            attribute(5, 2, 10);
            glVertexAttribIPointer(6, 1, GL_UNSIGNED_INT, STRIDE * Float.BYTES, 12L * Float.BYTES);
            glEnableVertexAttribArray(6);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, (long) indexCount * Integer.BYTES, GL_DYNAMIC_DRAW);
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        } finally {
            glBindVertexArray(0);
        }
    }

    private static void attribute(int location, int size, int offset) {
        glVertexAttribPointer(location, size, GL_FLOAT, false, STRIDE * Float.BYTES, (long) offset * Float.BYTES);
        glEnableVertexAttribArray(location);
    }

    void draw(Matrix4f transform) {
        if (vao == 0) return;
        boolean perspective = transform.m03() != 0 || transform.m13() != 0 || transform.m23() != 0;
        if (perspective) transform.perspectiveOrigin(eye);
        else eye.set(transform.m02(), transform.m12(), transform.m22());
        glBindVertexArray(vao);
        if (previousPerspective != perspective || !previousEye.equals(eye)) {
            sort(perspective);
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, ordered);
            previousEye.set(eye);
            previousPerspective = perspective;
        }
        glDrawElements(GL_TRIANGLES, indices.length, GL_UNSIGNED_INT, 0L);
    }

    private void sort(boolean perspective) {
        double cx = Math.floor(eye.x), cy = Math.floor(eye.y), cz = Math.floor(eye.z);
        for (var f : faces) {
            if (perspective) {
                // Cells encountered along any ray move one grid step farther at every boundary.
                f.shell = Math.abs(f.cellX - cx) + Math.abs(f.cellY - cy) + Math.abs(f.cellZ - cz);
                f.depth = eye.distanceSquared(f.x, f.y, f.z);
            } else {
                f.shell = f.cellX * Math.signum(eye.x) + f.cellY * Math.signum(eye.y) + f.cellZ * Math.signum(eye.z);
                f.depth = eye.dot(f.x, f.y, f.z);
            }
        }
        Arrays.sort(faces, FAR_FIRST);
        int next = 0;
        for (var f : faces) {
            System.arraycopy(indices, f.first, ordered, next, f.count);
            next += f.count;
        }
    }

    @Override public void close() {
        if (ebo != 0) glDeleteBuffers(ebo);
        if (vbo != 0) glDeleteBuffers(vbo);
        if (vao != 0) glDeleteVertexArrays(vao);
        ebo = vbo = vao = 0;
    }
}
