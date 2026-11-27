package com.mcworldexplorer.experimental.v05;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;

public final class GlfwOrbitController {
    private static final double ROTATION_SENSITIVITY = 0.008;
    private static final double ZOOM_BASE = 1.12;

    private OrbitCameraState state;
    private boolean dragging;
    private boolean cursorInitialized;
    private double lastX;
    private double lastY;

    public GlfwOrbitController(long window, OrbitCameraState initialState) {
        state = initialState;
        glfwSetMouseButtonCallback(window, (handle, button, action, modifiers) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                dragging = action == GLFW_PRESS;
                if (action == GLFW_RELEASE) {
                    cursorInitialized = false;
                }
            }
        });
        glfwSetCursorPosCallback(window, (handle, x, y) -> {
            if (!dragging) {
                cursorInitialized = false;
                return;
            }
            if (!cursorInitialized) {
                lastX = x;
                lastY = y;
                cursorInitialized = true;
                return;
            }
            state = state.rotate(
                    (x - lastX) * ROTATION_SENSITIVITY,
                    -(y - lastY) * ROTATION_SENSITIVITY);
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
                state = initialState.reset();
            } else if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(handle, true);
            }
        });
    }

    public OrbitCameraState state() {
        return state;
    }
}
