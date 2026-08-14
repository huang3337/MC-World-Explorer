package com.mcworldexplorer.voxel.render.lwjgl;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelShaderProgramTest {
    @Test
    void loadsPackagedShadersAndRejectsMissingResource() throws Exception {
        assertTrue(VoxelShaderProgram.read("/v05/shaders/voxel.vert").contains("uOffset"));
        assertTrue(VoxelShaderProgram.read("/v05/shaders/voxel.frag").contains("uColor"));
        assertThrows(IOException.class,
                () -> VoxelShaderProgram.read("/v05/shaders/missing.frag"));
    }
}
