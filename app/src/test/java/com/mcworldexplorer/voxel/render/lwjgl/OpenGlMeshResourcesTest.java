package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBatch;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        int material = 0x123456 | (1 << 25);
        float packedMaterial = Float.intBitsToFloat(material);
        float zeroBits = Float.intBitsToFloat(0);
        assertArrayEquals(new float[]{
                1, 2, 3, 0, 1, 0, 0, 0, packedMaterial, 1.0f, 0, 0, zeroBits,
                4, 5, 6, 1, 0, 0, 0, 0, packedMaterial, 1.0f, 0, 0, zeroBits}, OpenGlMeshResources.interleave(batch));
    }

    @Test
    void interleavesPerVertexColorsAndOverlayAttributes() {
        MeshBatch batch = new MeshBatch(
                RenderLayer.OPAQUE,
                0xFFFFFF,
                1.0f,
                1,
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[]{0, 1, 2},
                new float[]{0.1f, 0.2f, 0.3f, 0.2f, 0.3f, 0.4f},
                true,
                new int[]{0xFFFFFF, 0xFFFFFF, 0x79C05A},
                new float[]{0.5f, 0.6f, 0.7f, 0.6f, 0.0f, 0.0f},
                new int[]{0x91BD59, 0x91BD59, -1});

        int baseWithOverlay = 0xFFFFFF | (1 << 24) | (1 << 25) | (1 << 26);
        int baseOnly = 0x79C05A | (1 << 24) | (1 << 25);
        assertArrayEquals(new float[]{
                0, 0, 0, 0, 0, 1, 0.1f, 0.2f, Float.intBitsToFloat(baseWithOverlay), 1.0f, 0.5f, 0.6f, Float.intBitsToFloat(0x91BD59),
                1, 0, 0, 0, 0, 1, 0.3f, 0.2f, Float.intBitsToFloat(baseWithOverlay), 1.0f, 0.7f, 0.6f, Float.intBitsToFloat(0x91BD59),
                1, 1, 0, 0, 0, 1, 0.3f, 0.4f, Float.intBitsToFloat(baseOnly), 1.0f, 0.0f, 0.0f, Float.intBitsToFloat(0)
        }, OpenGlMeshResources.interleave(batch));
        assertEquals(0x79C05A, batch.vertexColor(2));
        assertTrue(batch.hasOverlay(0));
        assertFalse(batch.hasOverlay(2));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.vertexColor(3));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.hasOverlay(-1));
    }

    @Test
    void meshBatchValidatesNullArraysOverlayPrerequisitesAndVertexBounds() {
        MeshBatch untinted = new MeshBatch(
                RenderLayer.OPAQUE,
                0xABCDEF,
                1.0f,
                1,
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[]{0, 1, 2});
        assertEquals(0xABCDEF, untinted.vertexColor(0));
        assertFalse(untinted.hasOverlay(0));
        assertThrows(IndexOutOfBoundsException.class, () -> untinted.vertexColor(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> untinted.vertexColor(3));
        assertThrows(IndexOutOfBoundsException.class, () -> untinted.hasOverlay(3));
        assertThrows(NullPointerException.class, () -> new MeshBatch(
                RenderLayer.OPAQUE, 0xFFFFFF, 1.0f, 1,
                new float[]{0, 0, 0}, new float[]{0, 0, 1}, new int[]{0, 0, 0},
                new float[]{0.0f, 0.0f}, true, null, new float[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new MeshBatch(
                RenderLayer.OPAQUE, 0xFFFFFF, 1.0f, 1,
                new float[]{0, 0, 0}, new float[]{0, 0, 1}, new int[]{0, 0, 0},
                new float[0], true, new int[0], new float[]{0.1f, 0.2f}, new int[]{0x79C05A}));
    }
}
