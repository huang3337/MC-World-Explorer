package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Hidden GL pixel/lifecycle checks; does not automate human acceptance or keyboard input. */
class ViewerTargetOutlineIntegrationTest {
    private static final int SIZE = 384;

    @Test void drawsBlackEdgesWithOcclusionClearsTargetAndRestoresStateAcrossFiveGenerations() throws Exception {
        assumeTrue("1".equals(System.getenv("MCWE_R2_NATIVE_TEST")), "原生 GL 需显式启用");
        assertTrue(glfwInit());
        long window = 0;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            window = glfwCreateWindow(SIZE, SIZE, "R3 outline automatic GL validation", 0, 0);
            assertNotEquals(0, window);
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glViewport(0, 0, SIZE, SIZE);
            var projection = new Matrix4f().ortho(-1, 2, -1, 2, -5, 5);
            var box = new MeshBounds(0, 0, 0, 1, 1, 1);
            for (int generation = 0; generation < 5; generation++) {
                int vao, vbo, program;
                try (var outline = new ViewerTargetOutline()) {
                    vao = integerField(outline, "vao");
                    vbo = integerField(outline, "vbo");
                    var shaderField = ViewerTargetOutline.class.getDeclaredField("shader");
                    shaderField.setAccessible(true);
                    program = integerField(shaderField.get(outline), "program");
                    assertTrue(glIsVertexArray(vao)); assertTrue(glIsBuffer(vbo)); assertTrue(glIsProgram(program));
                    clear(1);
                    glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND);
                    glEnable(GL_CULL_FACE); glDepthMask(false); glDepthFunc(GL_GREATER);
                    glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ZERO, GL_ONE);
                    outline.draw(projection, box, SIZE, SIZE);
                    glFinish();
                    assertEquals(GL_NO_ERROR, glGetError());
                    assertFalse(glIsEnabled(GL_DEPTH_TEST)); assertFalse(glIsEnabled(GL_BLEND));
                    assertTrue(glIsEnabled(GL_CULL_FACE)); assertFalse(glGetBoolean(GL_DEPTH_WRITEMASK));
                    assertEquals(GL_GREATER, glGetInteger(GL_DEPTH_FUNC));
                    assertEquals(GL_ONE, glGetInteger(GL_BLEND_SRC_RGB));
                    assertEquals(GL_ZERO, glGetInteger(GL_BLEND_DST_RGB));
                    assertEquals(GL_ZERO, glGetInteger(GL_BLEND_SRC_ALPHA));
                    assertEquals(GL_ONE, glGetInteger(GL_BLEND_DST_ALPHA));
                    assertEquals(0, glGetInteger(GL_CURRENT_PROGRAM));
                    assertEquals(0, glGetInteger(GL_VERTEX_ARRAY_BINDING));
                    assertEquals(0, glGetInteger(GL_ARRAY_BUFFER_BINDING));
                    assertEquals(1, glGetFloat(GL_LINE_WIDTH));
                    var visible = image();
                    long dark = darkPixels(visible);
                    assertTrue(dark > 2000 && dark < 5000, "必须只绘制加粗黑边而非填充面: " + dark);
                    assertFivePixelWidth(visible, SIZE);
                    assertEquals(0xffffff, visible.getRGB(192, 192) & 0xffffff, "包围盒内部不填色");
                    String output = System.getProperty("mcwe.r3.output");
                    if (output != null && generation < 2) {
                        Files.createDirectories(Path.of(output));
                        javax.imageio.ImageIO.write(visible, "png", Path.of(output, "outline-width5-" + generation + ".png").toFile());
                    }
                    clear(0); // A nearer opaque depth surface must hide the target behind it.
                    outline.draw(projection, box, SIZE, SIZE);
                    assertEquals(0, darkPixels(image()), "线框不能透过前景实体");
                    clear(1);
                    outline.draw(projection, null, SIZE, SIZE);
                    assertEquals(0, darkPixels(image()), "目标消失时不绘制旧线框");
                    var moved = new MeshBounds(.6, .3, 0, .7, .8, 1);
                    outline.draw(projection, moved, SIZE, SIZE);
                    var changed = image();
                    assertEquals(0xffffff, changed.getRGB(128, 192) & 0xffffff, "换目标不残留旧边");
                    assertTrue(darkPixels(changed) > 100, "新薄片包围盒必须可见");
                    clear(1);
                    glViewport(0, 0, 256, 256);
                    outline.draw(projection, box, 256, 256);
                    assertFivePixelWidth(image(), 256);
                    glViewport(0, 0, SIZE, SIZE);
                    var foreign = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                    var thread = new Thread(() -> { try { outline.draw(projection, box, SIZE, SIZE); } catch (Throwable e) { foreign.set(e); } });
                    thread.start(); thread.join();
                    assertInstanceOf(IllegalStateException.class, foreign.get());
                    assertEquals(GL_NO_ERROR, glGetError());
                }
                assertFalse(glIsBuffer(vbo)); assertFalse(glIsVertexArray(vao)); assertFalse(glIsProgram(program));
            }
        } finally {
            if (window != 0) { GL.setCapabilities(null); glfwDestroyWindow(window); }
            glfwTerminate();
        }
    }

    private static void assertFivePixelWidth(BufferedImage image, int viewportSize) {
        int middleX = viewportSize / 2;
        int edgeY = SIZE - 1 - viewportSize / 3;
        int dark = 0;
        for (int y = edgeY - 10; y <= edgeY + 10; y++) if ((image.getRGB(middleX, y) & 255) < 128) dark++;
        assertEquals(5, dark, "前向兼容上下文中应实际绘制5像素宽，不只改变线宽参数");
    }

    private static void clear(double depth) {
        glDepthMask(true); glClearDepth(depth); glClearColor(1, 1, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private static int integerField(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(instance);
    }

    private static long darkPixels(BufferedImage image) {
        long count = 0;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) if ((image.getRGB(x, y) & 255) < 128) count++;
        return count;
    }

    private static BufferedImage image() {
        var pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int i = (y * SIZE + x) * 4;
                image.setRGB(x, SIZE - 1 - y, 0xff000000 | ((pixels.get(i) & 255) << 16)
                        | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
            }
            return image;
        } finally { MemoryUtil.memFree(pixels); }
    }
}
