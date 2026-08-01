package com.mcworldexplorer.experimental.v04.render.javafx;

import com.mcworldexplorer.experimental.v04.mesh.MeshBatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class JavaFxMeshAdapterTest {
    @Test
    void mapsEachSharedIndexToPointNormalAndDummyTextureIndex() {
        MeshBatch batch = new MeshBatch(
                0x123456,
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[]{0, 1, 2});

        assertArrayEquals(
                new int[]{0, 0, 0, 1, 1, 0, 2, 2, 0},
                JavaFxMeshAdapter.faceElements(batch));
    }
}
