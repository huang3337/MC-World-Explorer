package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBatch;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class OpenGlMeshResourcesTest {
    @Test
    void interleavesPositionsAndNormalsWithoutOpenGlContext() {
        MeshBatch batch = new MeshBatch(
                RenderLayer.OPAQUE,
                0x123456,
                1.0f,
                1,
                new float[]{1, 2, 3, 4, 5, 6},
                new float[]{0, 1, 0, 1, 0, 0},
                new int[]{0, 1, 0});

        assertArrayEquals(new float[]{
                1, 2, 3, 0, 1, 0,
                4, 5, 6, 1, 0, 0}, OpenGlMeshResources.interleave(batch));
    }
}
