package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.glfw.GLFW.*;

/** GLFW binding for both free and legacy orbit navigation. Owned by the render thread. */
final class GlfwViewerOrbitController {
    private final long window;
    private final ViewerNavigationState navigation;
    private boolean appliedCapture;

    GlfwViewerOrbitController(long window, MeshBounds bounds) {
        this.window = window;
        navigation = new ViewerNavigationState(bounds);
        navigation.focus(glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE);
        glfwSetMouseButtonCallback(window, (handle, button, action, modifiers) -> {
            try (var stack = MemoryStack.stackPush()) {
                var x = stack.mallocDouble(1); var y = stack.mallocDouble(1);
                glfwGetCursorPos(handle, x, y);
                navigation.mouseButton(button, action, x.get(0), y.get(0));
                syncCapture();
            }
        });
        glfwSetCursorPosCallback(window, (handle, x, y) -> {
            try (var stack = MemoryStack.stackPush()) {
                var w = stack.mallocInt(1); var h = stack.mallocInt(1);
                glfwGetWindowSize(handle, w, h);
                navigation.cursor(x, y, h.get(0));
            }
        });
        glfwSetScrollCallback(window, (handle, x, y) -> navigation.scroll(y));
        glfwSetKeyCallback(window, (handle, key, scanCode, action, modifiers) -> {
            navigation.key(key, action, modifiers); syncCapture();
            if (navigation.closeRequested()) glfwSetWindowShouldClose(handle, true);
        });
        glfwSetWindowFocusCallback(window, (handle, focused) -> { navigation.focus(focused); syncCapture(); });
        glfwSetWindowIconifyCallback(window, (handle, iconified) -> { navigation.iconify(iconified); syncCapture(); });
        glfwSetWindowCloseCallback(window, handle -> release());
    }
    void reset(MeshBounds bounds) { navigation.reset(bounds); syncCapture(); }
    void release() { navigation.release(); syncCapture(); }
    void update(double seconds) { navigation.update(seconds); }
    ViewerNavigationState navigation() { return navigation; }
    // Retained for orbit-only mathematical probes; rendering uses navigation.pose().
    ViewerOrbitCameraState state() { return navigation.orbit(); }
    private void syncCapture() {
        boolean captured = navigation.captured();
        if (captured == appliedCapture) return;
        appliedCapture = captured;
        glfwSetInputMode(window, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, captured ? GLFW_TRUE : GLFW_FALSE);
    }
    static boolean exceedsClickThreshold(double px, double py, double x, double y) {
        double dx = x - px, dy = y - py; return dx * dx + dy * dy > 16;
    }
    static double yawDeltaFor(double deltaX) { return -deltaX * .005; }
    static double pitchDeltaFor(double deltaY) { return deltaY * .005; }
}
