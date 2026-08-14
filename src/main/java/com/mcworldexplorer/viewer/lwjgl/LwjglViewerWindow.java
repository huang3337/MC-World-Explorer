package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.viewer.ViewerWindow;
import com.mcworldexplorer.viewer.ViewerWindowContent;
import com.mcworldexplorer.voxel.render.lwjgl.LwjglSceneRenderer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import static org.lwjgl.glfw.GLFW.glfwFocusWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
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
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glViewport;

public final class LwjglViewerWindow implements ViewerWindow {
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 800;

    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Thread ownerThread;

    private GLFWErrorCallback errorCallback;
    private LwjglSceneRenderer renderer;
    private GlfwViewerOrbitController controller;
    private long window;
    private boolean glfwInitialized;
    private boolean capabilitiesCreated;

    public LwjglViewerWindow() {
        ownerThread = new Thread(this::runLoop, "lwjgl-viewer-window");
        ownerThread.setDaemon(true);
        ownerThread.start();
    }

    @Override
    public void show(ViewerWindowContent content, Completion completion) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(completion, "completion");
        if (closeRequested.get()) {
            completion.onFailed(new IllegalStateException("viewer window is closed"));
            return;
        }
        commands.offer(new ShowCommand(content, completion));
    }

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        commands.offer(CloseCommand.INSTANCE);
        if (Thread.currentThread() == ownerThread) {
            return;
        }
        try {
            ownerThread.join(5_000);
            if (ownerThread.isAlive()) {
                ownerThread.interrupt();
                ownerThread.join(1_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        try {
            while (!closeRequested.get()) {
                Command command = window == 0
                        ? commands.poll(100, TimeUnit.MILLISECONDS)
                        : commands.poll();
                if (command != null) {
                    if (command == CloseCommand.INSTANCE) {
                        break;
                    }
                    handleShow((ShowCommand) command);
                }
                if (window != 0) {
                    if (glfwWindowShouldClose(window)) {
                        destroyWindow();
                    } else {
                        drawFrame();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            destroyWindow();
            terminateGlfw();
            failPendingCommands();
        }
    }

    private void handleShow(ShowCommand command) {
        if (!command.completion().isCurrent()) {
            return;
        }
        boolean creating = window == 0;
        try {
            if (creating) {
                if (!createWindow(command.content(), command.completion())) {
                    destroyWindow();
                    return;
                }
            } else {
                if (!renderer.replace(
                        command.content().snapshot(), command.completion()::isCurrent)) {
                    return;
                }
                controller.reset(command.content().snapshot().bounds());
                glfwSetWindowTitle(window, command.content().title());
                glfwFocusWindow(window);
            }
            command.completion().onShown();
        } catch (Throwable failure) {
            if (creating) {
                destroyWindow();
            }
            command.completion().onFailed(failure);
        }
    }

    private boolean createWindow(
            ViewerWindowContent content,
            Completion completion) throws Exception {
        initializeGlfw();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window = glfwCreateWindow(
                DEFAULT_WIDTH, DEFAULT_HEIGHT, content.title(), 0, 0);
        if (window == 0) {
            throw new IllegalStateException("无法创建 OpenGL 3.3 Core 三维窗口");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        capabilitiesCreated = true;
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClearColor(0.118f, 0.133f, 0.149f, 1.0f);
        renderer = new LwjglSceneRenderer();
        if (!renderer.replace(content.snapshot(), completion::isCurrent)) {
            return false;
        }
        controller = new GlfwViewerOrbitController(window, content.snapshot().bounds());
        glfwShowWindow(window);
        glfwFocusWindow(window);
        return true;
    }

    private void initializeGlfw() {
        if (glfwInitialized) {
            return;
        }
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW 初始化失败");
        }
        glfwInitialized = true;
    }

    private void drawFrame() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            int framebufferWidth = Math.max(1, width.get(0));
            int framebufferHeight = Math.max(1, height.get(0));
            glViewport(0, 0, framebufferWidth, framebufferHeight);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            renderer.draw(ViewerCameraMatrices.viewProjection(
                    controller.state(), framebufferWidth, framebufferHeight));
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void destroyWindow() {
        if (window == 0) {
            return;
        }
        try {
            if (renderer != null) {
                renderer.close();
            }
        } finally {
            renderer = null;
            controller = null;
            if (capabilitiesCreated) {
                GL.setCapabilities(null);
                capabilitiesCreated = false;
            }
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = 0;
        }
    }

    private void terminateGlfw() {
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
        }
        if (errorCallback != null) {
            glfwSetErrorCallback(null);
            errorCallback.free();
            errorCallback = null;
        }
    }

    private void failPendingCommands() {
        Command command;
        while ((command = commands.poll()) != null) {
            if (command instanceof ShowCommand show) {
                show.completion().onFailed(new IllegalStateException("viewer window is closed"));
            }
        }
    }

    private sealed interface Command permits ShowCommand, CloseCommand {
    }

    private record ShowCommand(
            ViewerWindowContent content,
            Completion completion) implements Command {
    }

    private enum CloseCommand implements Command {
        INSTANCE
    }
}
