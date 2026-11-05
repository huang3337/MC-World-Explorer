package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import com.mcworldexplorer.voxel.mesh.VoxelTargetIndex;
import com.mcworldexplorer.voxel.render.lwjgl.VoxelShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/** A depth-tested selection box; shares the HUD hit and never queries world resources. */
final class ViewerTargetOutline implements AutoCloseable {
    private static final double EXPANSION = 0.002;
    private static final float LINE_WIDTH_PIXELS = 5;
    private static final int MAX_VERTEX_COUNT = 12 * 6;
    private static final int FLOATS_PER_VERTEX = 6;
    private static final float[] EDGES = {
            0,0,0, 1,0,0,  1,0,0, 1,0,1,  1,0,1, 0,0,1,  0,0,1, 0,0,0,
            0,1,0, 1,1,0,  1,1,0, 1,1,1,  1,1,1, 0,1,1,  0,1,1, 0,1,0,
            0,0,0, 0,1,0,  1,0,0, 1,1,0,  1,0,1, 1,1,1,  0,0,1, 0,1,1
    };
    private final Thread ownerThread = Thread.currentThread();
    private final VoxelShaderProgram shader;
    private int vao;
    private int vbo;

    ViewerTargetOutline() throws IOException {
        shader = new VoxelShaderProgram();
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            if (vao == 0 || vbo == 0) throw new IllegalStateException("无法创建方块目标线框资源");
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTEX_COUNT * FLOATS_PER_VERTEX * Float.BYTES, GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * Float.BYTES, 0L);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            glBindVertexArray(oldVao);
            glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
        }
    }

    static MeshBounds bounds(VoxelTargetIndex.Hit hit, long anchorX, long anchorZ) {
        if (hit == null || hit.entry().box() == null) return null;
        MeshBounds box = hit.entry().box();
        // Subtract before converting to float, preserving negative/far-world selection precision.
        double x = (double) hit.x() - anchorX, y = hit.y(), z = (double) hit.z() - anchorZ;
        return new MeshBounds(x + box.minX() - EXPANSION, y + box.minY() - EXPANSION,
                z + box.minZ() - EXPANSION, x + box.maxX() + EXPANSION,
                y + box.maxY() + EXPANSION, z + box.maxZ() + EXPANSION);
    }

    void draw(Matrix4f viewProjection, MeshBounds box, int viewportWidth, int viewportHeight) {
        requireOwnerThread();
        if (box == null || vao == 0 || viewportWidth <= 0 || viewportHeight <= 0) return;
        boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunction = glGetInteger(GL_DEPTH_FUNC), program = glGetInteger(GL_CURRENT_PROGRAM);
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int sourceRgb = glGetInteger(GL_BLEND_SRC_RGB), destinationRgb = glGetInteger(GL_BLEND_DST_RGB);
        int sourceAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), destinationAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var transform = new Matrix4f(viewProjection).translate((float) box.minX(), (float) box.minY(), (float) box.minZ())
                    .scale((float) (box.maxX() - box.minX()), (float) (box.maxY() - box.minY()), (float) (box.maxZ() - box.minZ()));
            var vertices = stack.mallocFloat(MAX_VERTEX_COUNT * FLOATS_PER_VERTEX);
            int count = vertices(transform, vertices, viewportWidth, viewportHeight);
            if (count == 0) return;
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0L, vertices.flip());
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            glEnable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            shader.use();
            // Vertices already use normalized screen coordinates, including the original projected depth.
            shader.setMvp(new Matrix4f());
            shader.setOffset(0, 0);
            shader.setColor(0, .65f);
            shader.setTextured(false);
            shader.setShaded(false);
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, count);
        } finally {
            glBindVertexArray(oldVao);
            glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
            glUseProgram(program);
            if (cull) glEnable(GL_CULL_FACE);
            glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            if (!blend) glDisable(GL_BLEND);
            glDepthMask(depthMask);
            glDepthFunc(depthFunction);
            if (!depth) glDisable(GL_DEPTH_TEST);
        }
    }

    /** Expand each projected edge to a five-framebuffer-pixel ribbon, independent of driver wide-line support. */
    static int vertices(Matrix4f transform, FloatBuffer output, int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        int start = output.position();
        var a = new Vector4f(); var b = new Vector4f();
        for (int i = 0; i < EDGES.length; i += 6) {
            transform.transform(a.set(EDGES[i], EDGES[i + 1], EDGES[i + 2], 1));
            transform.transform(b.set(EDGES[i + 3], EDGES[i + 4], EDGES[i + 5], 1));
            if (!a.isFinite() || !b.isFinite() || !clipDepth(a, b, true) || !clipDepth(a, b, false)
                    || a.w <= 1e-6f || b.w <= 1e-6f) continue;
            a.div(a.w); b.div(b.w);
            float dx = (b.x - a.x) * width / 2, dy = (b.y - a.y) * height / 2;
            float length = (float) Math.hypot(dx, dy);
            if (!Float.isFinite(length) || length < 1e-5f) continue;
            float nx = -dy / length * LINE_WIDTH_PIXELS / width;
            float ny = dx / length * LINE_WIDTH_PIXELS / height;
            float tx = dx / length * LINE_WIDTH_PIXELS / width;
            float ty = dy / length * LINE_WIDTH_PIXELS / height;
            // Square end caps keep adjacent box edges joined at their corners.
            a.x -= tx; a.y -= ty; b.x += tx; b.y += ty;
            vertex(output, a.x + nx, a.y + ny, a.z);
            vertex(output, a.x - nx, a.y - ny, a.z);
            vertex(output, b.x + nx, b.y + ny, b.z);
            vertex(output, a.x - nx, a.y - ny, a.z);
            vertex(output, b.x - nx, b.y - ny, b.z);
            vertex(output, b.x + nx, b.y + ny, b.z);
        }
        return (output.position() - start) / FLOATS_PER_VERTEX;
    }

    private static boolean clipDepth(Vector4f a, Vector4f b, boolean near) {
        float da = near ? a.w + a.z : a.w - a.z;
        float db = near ? b.w + b.z : b.w - b.z;
        if (da < 0 && db < 0) return false;
        if (da < 0) a.lerp(b, da / (da - db));
        else if (db < 0) b.lerp(a, db / (db - da));
        return true;
    }

    private static void vertex(FloatBuffer output, float x, float y, float z) {
        output.put(x).put(y).put(z).put(0).put(1).put(0);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("目标线框只能在 OpenGL 所属线程使用");
    }

    @Override public void close() {
        requireOwnerThread();
        if (vbo != 0) { glDeleteBuffers(vbo); vbo = 0; }
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        shader.close();
    }
}
