package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.mesh.ChunkMeshSnapshot;
import com.mcworldexplorer.voxel.mesh.MeshBatch;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OpenGlSceneResources implements AutoCloseable {
    private final List<OpenGlMeshResources> meshes;
    private final OpenGlTextureResources texture;

    private OpenGlSceneResources(List<OpenGlMeshResources> meshes, OpenGlTextureResources texture) {
        this.meshes = List.copyOf(meshes);
        this.texture = texture;
    }

    public static OpenGlSceneResources upload(VoxelSceneSnapshot scene) {
        List<UploadItem> items = new ArrayList<>();
        for (ChunkMeshSnapshot chunk : scene.chunks()) {
            long offsetX = Math.subtractExact(chunk.worldOriginX(), scene.anchorWorldX());
            long offsetZ = Math.subtractExact(chunk.worldOriginZ(), scene.anchorWorldZ());
            for (MeshBatch batch : chunk.batches()) {
                items.add(new UploadItem(chunk, batch, Math.toIntExact(offsetX), Math.toIntExact(offsetZ)));
            }
        }
        items.sort(Comparator.comparing((UploadItem item) -> item.batch().layer())
                .thenComparing(item -> item.chunk().coordinate())
                .thenComparingInt(item -> item.batch().rgb()));

        List<OpenGlMeshResources> uploaded = new ArrayList<>();
        OpenGlTextureResources texture = null;
        try {
            if (scene.atlas().isPresent()) texture = new OpenGlTextureResources(scene.atlas().orElseThrow());
            for (UploadItem item : items) {
                uploaded.add(OpenGlMeshResources.upload(
                        item.batch(), item.offsetX(), item.offsetZ()));
            }
            return new OpenGlSceneResources(uploaded, texture);
        } catch (RuntimeException | Error failure) {
            closeReverse(uploaded);
            if (texture != null) texture.close();
            throw failure;
        }
    }

    public void bindTexture() {
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        if (texture != null) texture.bind();
        else org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
    }

    public List<OpenGlMeshResources> meshes() {
        return meshes;
    }

    @Override
    public void close() {
        closeReverse(meshes);
        if (texture != null) texture.close();
    }

    private static void closeReverse(List<OpenGlMeshResources> resources) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            resources.get(index).close();
        }
    }

    private record UploadItem(
            ChunkMeshSnapshot chunk,
            MeshBatch batch,
            int offsetX,
            int offsetZ) {
    }
}
