package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;

final class GlfwViewerOrbitController {
    private static final double ROTATION_SENSITIVITY = 0.005;
    private static final double ZOOM_BASE = 1.12;
    private static final double CLICK_DRAG_THRESHOLD = 4.0;

    private ViewerOrbitCameraState state;
    private DragMode dragMode = DragMode.NONE;
    private int activeButton = -1;
    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;

    GlfwViewerOrbitController(long window, MeshBounds bounds) {
        reset(bounds);
        glfwSetMouseButtonCallback(window, (handle, button, action, modifiers) -> {
            if (action == GLFW_PRESS
                    && (button == GLFW_MOUSE_BUTTON_LEFT
                    || button == GLFW_MOUSE_BUTTON_RIGHT)) {
                CursorPosition cursor = cursorPosition(handle);
                activeButton = button;
                pressX = cursor.x();
                pressY = cursor.y();
                lastX = cursor.x();
                lastY = cursor.y();
                dragMode = button == GLFW_MOUSE_BUTTON_LEFT
                        ? DragMode.LEFT_PENDING
                        : DragMode.PANNING;
            } else if (action == GLFW_RELEASE && button == activeButton) {
                dragMode = DragMode.NONE;
                activeButton = -1;
            }
        });
        glfwSetCursorPosCallback(window, (handle, x, y) -> {
            if (dragMode == DragMode.NONE) {
                return;
            }
            if (dragMode == DragMode.LEFT_PENDING) {
                if (!exceedsClickThreshold(pressX, pressY, x, y)) {
                    return;
                }
                dragMode = DragMode.ROTATING;
                state = state.rotate(
                        yawDeltaFor(x - pressX),
                        pitchDeltaFor(y - pressY));
                lastX = x;
                lastY = y;
                return;
            }
            double deltaX = x - lastX;
            double deltaY = y - lastY;
            if (dragMode == DragMode.ROTATING) {
                state = state.rotate(
                        yawDeltaFor(deltaX),
                        pitchDeltaFor(deltaY));
            } else if (dragMode == DragMode.PANNING) {
                state = state.panPixels(deltaX, deltaY, windowHeight(handle));
            }
            lastX = x;
            lastY = y;
        });
        glfwSetScrollCallback(window, (handle, xOffset, yOffset) ->
                state = state.zoomBy(Math.pow(ZOOM_BASE, -yOffset)));
        glfwSetKeyCallback(window, (handle, key, scanCode, action, modifiers) -> {
            if (action != GLFW_PRESS) {
                return;
            }
            if (key == GLFW_KEY_R) {
                state = state.reset();
            } else if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(handle, true);
            }
        });
    }

    void reset(MeshBounds bounds) {
        state = ViewerOrbitCameraState.forBounds(bounds);
        dragMode = DragMode.NONE;
        activeButton = -1;
    }

    ViewerOrbitCameraState state() {
        return state;
    }

    static boolean exceedsClickThreshold(
            double pressX,
            double pressY,
            double currentX,
            double currentY) {
        double deltaX = currentX - pressX;
        double deltaY = currentY - pressY;
        return deltaX * deltaX + deltaY * deltaY
                > CLICK_DRAG_THRESHOLD * CLICK_DRAG_THRESHOLD;
    }

    static double yawDeltaFor(double deltaX) {
        return -deltaX * ROTATION_SENSITIVITY;
    }

    static double pitchDeltaFor(double deltaY) {
        return deltaY * ROTATION_SENSITIVITY;
    }

    private static CursorPosition cursorPosition(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(window, x, y);
            return new CursorPosition(x.get(0), y.get(0));
        }
    }

    private static int windowHeight(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetWindowSize(window, width, height);
            return Math.max(1, height.get(0));
        }
    }

    private enum DragMode {
        NONE,
        LEFT_PENDING,
        ROTATING,
        PANNING
    }

    private record CursorPosition(double x, double y) {
    }
}
