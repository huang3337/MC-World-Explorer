package com.mcworldexplorer.experimental.v04.render.lwjgl;

import com.mcworldexplorer.experimental.v04.mesh.MeshBounds;
import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LwjglCameraMatricesTest {
    @Test
    void createsFiniteViewProjectionForSharedCamera() {
        OrbitCameraState camera = OrbitCameraState.forBounds(
                new MeshBounds(-16, -64, 32, 0, 320, 48));
        float[] values = new float[16];

        LwjglCameraMatrices.viewProjection(camera, 1280, 800).get(values);

        for (float value : values) {
            assertTrue(Float.isFinite(value));
        }
    }
}
