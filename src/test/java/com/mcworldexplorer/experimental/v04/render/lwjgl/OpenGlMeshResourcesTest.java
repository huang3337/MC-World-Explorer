package com.mcworldexplorer.experimental.v04.render.lwjgl;

import com.mcworldexplorer.experimental.v04.mesh.MeshBatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class OpenGlMeshResourcesTest {
    @Test
    void interleavesPositionAndNormalWithoutGraphicsContext() {
        MeshBatch batch = new MeshBatch(
                0,
                new float[]{1, 2, 3, 4, 5, 6},
                new float[]{0, 1, 0, 1, 0, 0},
                new int[]{0, 1, 0});

        assertArrayEquals(
                new float[]{1, 2, 3, 0, 1, 0, 4, 5, 6, 1, 0, 0},
                OpenGlMeshResources.interleave(batch));
    }
}
