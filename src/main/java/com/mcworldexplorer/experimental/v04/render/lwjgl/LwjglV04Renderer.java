package com.mcworldexplorer.experimental.v04.render.lwjgl;

import com.mcworldexplorer.experimental.v04.V04ScreenshotWriter;
import com.mcworldexplorer.experimental.v04.V04TrialContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

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
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.glBindVertexArray;

public final class LwjglV04Renderer implements AutoCloseable {
    private final V04TrialContext context;
    private final List<OpenGlMeshResources> meshes = new ArrayList<>();
    private GLFWErrorCallback errorCallback;
    private ShaderProgram shader;
    private long window;
    private boolean glfwInitialized;
    private boolean capabilitiesCreated;

    public LwjglV04Renderer(V04TrialContext context) {
        this.context = context;
    }

    public void run() throws IOException {
        initializeWindow();
        GL.createCapabilities();
        capabilitiesCreated = true;
        System.out.println("OpenGL vendor=" + glGetString(GL_VENDOR));
        System.out.println("OpenGL renderer=" + glGetString(GL_RENDERER));
        System.out.println("OpenGL version=" + glGetString(GL_VERSION));
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClearColor(0.118f, 0.133f, 0.149f, 1.0f);

        long uploadStart = System.nanoTime();
        shader = new ShaderProgram("/v04/shaders/voxel.vert", "/v04/shaders/voxel.frag");
        context.snapshot().batches().forEach(batch -> meshes.add(OpenGlMeshResources.upload(batch)));
        context.metrics().recordBackendUpload(System.nanoTime() - uploadStart);

        GlfwOrbitController controller = new GlfwOrbitController(window, context.camera());
        long renderStart = System.nanoTime();
        long previousFrame = 0;
        boolean screenshotWritten = false;
        long autoCloseAt = context.arguments().autoCloseSeconds() == 0
                ? Long.MAX_VALUE
                : System.nanoTime() + context.arguments().autoCloseSeconds() * 1_000_000_000L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            while (!glfwWindowShouldClose(window)) {
                long frameStart = System.nanoTime();
                width.clear();
                height.clear();
                glfwGetFramebufferSize(window, width, height);
                int framebufferWidth = Math.max(1, width.get(0));
                int framebufferHeight = Math.max(1, height.get(0));
                int motionAfter = context.arguments().motionAfterSeconds();
                double elapsedSeconds = (System.nanoTime() - renderStart) / 1_000_000_000.0;
                if (motionAfter >= 0 && elapsedSeconds >= motionAfter) {
                    controller.applyAutomatedMotion(elapsedSeconds - motionAfter);
                }
                glViewport(0, 0, framebufferWidth, framebufferHeight);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                shader.use();
                shader.setMvp(LwjglCameraMatrices.viewProjection(
                        controller.state(), framebufferWidth, framebufferHeight));
                for (OpenGlMeshResources mesh : meshes) {
                    shader.setColor(mesh.rgb());
                    glBindVertexArray(mesh.vertexArray());
                    glDrawElements(GL_TRIANGLES, mesh.indexCount(), GL_UNSIGNED_INT, 0L);
                }
                glBindVertexArray(0);
                if (!screenshotWritten && context.arguments().screenshot().isPresent()) {
                    writeScreenshot(framebufferWidth, framebufferHeight);
                    screenshotWritten = true;
                }
                glfwSwapBuffers(window);
                context.metrics().markFirstFrame(System.nanoTime());
                if (previousFrame != 0) {
                    context.metrics().frameTimes().record(frameStart - previousFrame);
                }
                previousFrame = frameStart;
                glfwPollEvents();
                if (System.nanoTime() >= autoCloseAt) {
                    org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose(window, true);
                }
            }
        }
    }

    private void writeScreenshot(int width, int height) throws IOException {
        ByteBuffer rgba = BufferUtils.createByteBuffer(Math.multiplyExact(Math.multiplyExact(width, height), 4));
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
        new V04ScreenshotWriter().write(
                context.arguments().screenshot().orElseThrow(), width, height, argb, true);
        System.out.println("V0.4 screenshot: " + context.arguments().screenshot().orElseThrow());
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
        window = glfwCreateWindow(1280, 800, "MC World Explorer V0.4 LWJGL Trial", 0, 0);
        if (window == 0) {
            throw new IllegalStateException("failed to create an OpenGL 3.3 Core window");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    @Override
    public void close() {
        for (int index = meshes.size() - 1; index >= 0; index--) {
            meshes.get(index).close();
        }
        meshes.clear();
        if (shader != null) {
            shader.close();
            shader = null;
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
