package com.mcworldexplorer.voxel.render.lwjgl;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelShaderProgramTest {
    @Test
    void loadsPackagedShadersAndRejectsMissingResource() throws Exception {
        String vert = VoxelShaderProgram.read("/v05/shaders/voxel.vert");
        String frag = VoxelShaderProgram.read("/v05/shaders/voxel.frag");
        assertTrue(vert.contains("uOffset"));
        assertTrue(vert.contains("aOverlayUv"));
        assertTrue(vert.contains("aOverlayMaterial"));
        assertTrue(frag.contains("vColor"));
        assertTrue(frag.contains("vHasOverlay"));
        assertTrue(frag.contains("vOverlayColor"));
        assertThrows(IOException.class,
                () -> VoxelShaderProgram.read("/v05/shaders/missing.frag"));
    }
}
