package com.mcworldexplorer.experimental.v04.render.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderProgramTest {
    @Test
    void shaderResourcesContainExpectedEntrypoints() throws Exception {
        String vertex = ShaderProgram.read("/v04/shaders/voxel.vert");
        String fragment = ShaderProgram.read("/v04/shaders/voxel.frag");

        assertTrue(vertex.contains("void main"));
        assertTrue(vertex.contains("uMvp"));
        assertTrue(fragment.contains("void main"));
        assertTrue(fragment.contains("uColor"));
    }
}
