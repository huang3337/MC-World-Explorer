package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import com.mcworldexplorer.voxel.render.lwjgl.LwjglSceneRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;

public final class LwjglV05Application implements AutoCloseable {
    private final V05ValidationContext context;
    private GLFWErrorCallback errorCallback;
    private LwjglSceneRenderer renderer;
    private long window;
    private boolean glfwInitialized;
    private boolean capabilitiesCreated;

    public LwjglV05Application(V05ValidationContext context) {
        this.context = context;
    }

    public V05RenderMetrics run() throws IOException {
        initializeWindow();
        GL.createCapabilities();
        capabilitiesCreated = true;
        String vendor = value(glGetString(GL_VENDOR));
        String rendererName = value(glGetString(GL_RENDERER));
        String version = value(glGetString(GL_VERSION));
        System.out.println("OpenGL vendor=" + vendor);
        System.out.println("OpenGL renderer=" + rendererName);
        System.out.println("OpenGL version=" + version);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClearColor(0.118f, 0.133f, 0.149f, 1.0f);

        VoxelSceneSnapshot scene = context.sceneResult().snapshot().orElseThrow();
        renderer = new LwjglSceneRenderer();
        long uploadStart = System.nanoTime();
        renderer.replace(scene);
        long uploadNanos = System.nanoTime() - uploadStart;
        GlfwOrbitController controller = new GlfwOrbitController(
                window, OrbitCameraState.forBounds(scene.bounds()));

        long renderStart = System.nanoTime();
        long firstFrameNanos = 0;
        long frameCount = 0;
        boolean screenshotWritten = false;
        long closeAt = context.arguments().durationSeconds() == 0
                ? Long.MAX_VALUE
                : renderStart + context.arguments().durationSeconds() * 1_000_000_000L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            while (!glfwWindowShouldClose(window)) {
                width.clear();
                height.clear();
                glfwGetFramebufferSize(window, width, height);
                int framebufferWidth = Math.max(1, width.get(0));
                int framebufferHeight = Math.max(1, height.get(0));
                glViewport(0, 0, framebufferWidth, framebufferHeight);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                renderer.draw(LwjglCameraMatrices.viewProjection(
                        controller.state(), framebufferWidth, framebufferHeight));
                if (!screenshotWritten && context.arguments().screenshot().isPresent()) {
                    writeScreenshot(framebufferWidth, framebufferHeight);
                    screenshotWritten = true;
                }
                glfwSwapBuffers(window);
                glfwPollEvents();
                frameCount++;
                if (firstFrameNanos == 0) {
                    firstFrameNanos = System.nanoTime() - renderStart;
                }
                if (System.nanoTime() >= closeAt) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        }
        double seconds = Math.max(1.0e-9, (System.nanoTime() - renderStart) / 1_000_000_000.0);
        return new V05RenderMetrics(
                uploadNanos,
                firstFrameNanos,
                frameCount,
                frameCount / seconds,
                vendor,
                rendererName,
                version);
    }

    private void writeScreenshot(int width, int height) throws IOException {
        ByteBuffer rgba = BufferUtils.createByteBuffer(
                Math.multiplyExact(Math.multiplyExact(width, height), 4));
        glReadPixels(0, 0, width, height, GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, rgba);
        int[] argb = new int[Math.multiplyExact(width, height)];
        for (int index = 0; index < argb.length; index++) {
            int offset = index * 4;
            int red = rgba.get(offset) & 0xFF;
            int green = rgba.get(offset + 1) & 0xFF;
            int blue = rgba.get(offset + 2) & 0xFF;
            int alpha = rgba.get(offset + 3) & 0xFF;
            argb[index] = alpha << 24 | red << 16 | green << 8 | blue;
        }
        new V05ScreenshotWriter().write(
                context.arguments().screenshot().orElseThrow(), width, height, argb, true);
    }

    private void initializeWindow() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW initialization failed");
        }
        glfwInitialized = true;
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(1280, 800, "MC World Explorer V0.5 Internal Validation", 0, 0);
        if (window == 0) {
            throw new IllegalStateException("failed to create an OpenGL 3.3 Core window");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private static String value(String value) {
        return value == null ? "unknown" : value;
    }

    @Override
    public void close() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (capabilitiesCreated) {
            GL.setCapabilities(null);
            capabilitiesCreated = false;
        }
        if (window != 0) {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = 0;
        }
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
        }
        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
        }
    }
}
