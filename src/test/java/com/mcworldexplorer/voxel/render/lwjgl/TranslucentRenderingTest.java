package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.mesh.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

class TranslucentRenderingTest {
    @Test void interleavesSheetsAcrossMaterialsAndChunkBoundaryFromBothSides() throws Exception {
        assumeTrue("1".equals(System.getenv("MCWE_R2_NATIVE_TEST")));
        assertTrue(glfwInit());
        long window = 0;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(64, 64, "Translucent sheets", 0, 0);
            assertNotEquals(0, window);
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            var first = chunk(0, List.of(sheets(RenderLayer.TRANSLUCENT, 0xff0000, 14.25f, 15.25f),
                    sheets(RenderLayer.FLUID, 0x0000ff, 14.75f)));
            var next = chunk(1, List.of(sheets(RenderLayer.FLUID, 0x0000ff, .25f)));
            var scene = new VoxelSceneSnapshot(0, 0, List.of(first, next),
                    new MeshBounds(0, 0, 14, 1, 1, 17), 4, 4, 16, 24, List.of());
            glEnable(GL_DEPTH_TEST);
            glViewport(0, 0, 64, 64);
            try (var renderer = new LwjglSceneRenderer()) {
                renderer.replace(scene);
                for (boolean perspective : List.of(true, false)) {
                    for (int z : new int[]{20, 10, 20}) {
                        var m = perspective ? new Matrix4f().perspective(1, 1, .1f, 40)
                                : new Matrix4f().ortho(-1, 1, -1, 1, .1f, 40);
                        m.lookAt(.5f, .5f, z, .5f, .5f, 15, 0, 1, 0);
                        glClearColor(0, 0, 0, 1);
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                        renderer.draw(m);
                        try (var stack = MemoryStack.stackPush()) {
                            var pixel = stack.malloc(4);
                            glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                            // Four 50% alpha sheets blend to 5/16 far color and 10/16 near color.
                            assertEquals(z > 15 ? 80 : 160, pixel.get(0) & 255, 1);
                            assertEquals(z > 15 ? 160 : 80, pixel.get(2) & 255, 1);
                        }
                        assertTrue(glGetBoolean(GL_DEPTH_WRITEMASK));
                        assertEquals(GL_NO_ERROR, glGetError());
                    }
                }
            }
        } finally {
            if (window != 0) {
                GL.setCapabilities(null);
                glfwDestroyWindow(window);
            }
            glfwTerminate();
        }
    }

    private static MeshBatch sheets(RenderLayer layer, int color, float... depths) {
        float[] positions = new float[depths.length * 12];
        float[] normals = new float[positions.length];
        int[] indices = new int[depths.length * 6];
        for (int i = 0; i < depths.length; i++) {
            float z = depths[i];
            System.arraycopy(new float[]{0,0,z, 1,0,z, 1,1,z, 0,1,z}, 0, positions, i * 12, 12);
            for (int v = 0; v < 4; v++) normals[i * 12 + v * 3 + 2] = 1;
            int base = i * 4;
            System.arraycopy(new int[]{base, base+1, base+2, base, base+2, base+3}, 0, indices, i * 6, 6);
        }
        return new MeshBatch(layer, color, .5f, depths.length, positions, normals, indices, new float[0], false);
    }

    private static ChunkMeshSnapshot chunk(int z, List<MeshBatch> batches) {
        int sheets = batches.stream().mapToInt(MeshBatch::faceCount).sum();
        return new ChunkMeshSnapshot(new ChunkCoordinate(0, z), 0, 16L * z, batches,
                new MeshBounds(0, 0, 0, 1, 1, 16), sheets, sheets, sheets * 4, sheets * 6, List.of());
    }
}
