package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL30.glBindVertexArray;

public final class LwjglSceneRenderer implements AutoCloseable {
    private final Thread ownerThread;
    private final VoxelShaderProgram shader;
    private OpenGlSceneResources scene;

    public LwjglSceneRenderer() throws IOException {
        ownerThread = Thread.currentThread();
        shader = new VoxelShaderProgram();
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void replace(VoxelSceneSnapshot snapshot) {
        replace(snapshot, () -> true);
    }

    public boolean replace(VoxelSceneSnapshot snapshot, BooleanSupplier commitAllowed) {
        requireOwnerThread();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(commitAllowed, "commitAllowed");
        OpenGlSceneResources replacement = OpenGlSceneResources.upload(snapshot);
        if (!commitAllowed.getAsBoolean()) {
            replacement.close();
            return false;
        }
        OpenGlSceneResources previous = scene;
        scene = replacement;
        if (previous != null) {
            previous.close();
        }
        return true;
    }

    public void draw(Matrix4f viewProjection) {
        requireOwnerThread();
        if (scene == null) {
            return;
        }
        shader.use();
        shader.setMvp(viewProjection);
        for (OpenGlMeshResources mesh : scene.meshes()) {
            shader.setOffset(mesh.offsetX(), mesh.offsetZ());
            shader.setColor(mesh.rgb(), mesh.alpha());
            glBindVertexArray(mesh.vertexArray());
            glDrawElements(GL_TRIANGLES, mesh.indexCount(), GL_UNSIGNED_INT, 0L);
        }
        glBindVertexArray(0);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("OpenGL renderer used from a non-owner thread");
        }
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (scene != null) {
            scene.close();
            scene = null;
        }
        shader.close();
    }
}
