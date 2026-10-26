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
        try {
            if (!commitAllowed.getAsBoolean()) { replacement.close(); return false; }
        } catch (RuntimeException | Error failure) { replacement.close(); throw failure; }
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
        scene.bindTexture();
        var ordered=new java.util.ArrayList<>(scene.meshes());
        ordered.sort((a,b)->{
            boolean ta=a.layer()==com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT||a.layer()==com.mcworldexplorer.voxel.mesh.RenderLayer.FLUID;
            boolean tb=b.layer()==com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT||b.layer()==com.mcworldexplorer.voxel.mesh.RenderLayer.FLUID;
            if(ta!=tb)return ta?1:-1;
            return ta?Float.compare(b.projectedDepth(viewProjection),a.projectedDepth(viewProjection)):0;
        });
        try {
        for (OpenGlMeshResources mesh : ordered) {
            org.lwjgl.opengl.GL11.glDepthMask(mesh.layer()!=com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT
                    &&mesh.layer()!=com.mcworldexplorer.voxel.mesh.RenderLayer.FLUID);
            shader.setOffset(mesh.offsetX(), mesh.offsetZ());
            shader.setColor(mesh.rgb(), mesh.alpha());
            shader.setTextured(mesh.textured());
            shader.setShaded(mesh.shaded());
            glBindVertexArray(mesh.vertexArray());
            glDrawElements(GL_TRIANGLES, mesh.indexCount(), GL_UNSIGNED_INT, 0L);
        }
        } finally { org.lwjgl.opengl.GL11.glDepthMask(true); glBindVertexArray(0); }
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
