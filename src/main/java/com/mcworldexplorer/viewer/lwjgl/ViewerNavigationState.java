package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import java.util.HashSet;
import java.util.Set;
import static org.lwjgl.glfw.GLFW.*;

/** Pure input state; all mutations belong to the GLFW owner thread. */
final class ViewerNavigationState {
    private MeshBounds bounds;
    private ViewerOrbitCameraState orbit;
    private ViewerFreeCameraState free;
    private final Set<Integer> held = new HashSet<>();
    private boolean orbitMode, captured, focused, iconified, clickRequired;
    private boolean altCandidate, altWasCaptured, closeRequested, cursorReady;
    private int altKey = -1, dragButton = -1;
    private double lastX, lastY, pressX, pressY;
    private boolean rotating;

    ViewerNavigationState(MeshBounds bounds) { reset(bounds); }
    void reset(MeshBounds next) {
        bounds = next; orbit = ViewerOrbitCameraState.forBounds(next); free = ViewerFreeCameraState.from(orbit.pose());
        orbitMode = false; closeRequested = false; release(); clickRequired = true;
    }
    void release() {
        captured = false; held.clear(); cursorReady = false; dragButton = -1; rotating = false;
        altCandidate = false; altKey = -1;
    }
    void focus(boolean value) {
        focused = value;
        if (!value) { release(); clickRequired = true; }
    }
    void iconify(boolean value) {
        iconified = value;
        if (value) { release(); clickRequired = true; }
    }
    private void capture() {
        if (!focused || iconified || orbitMode) return;
        held.clear(); captured = true; cursorReady = false; dragButton = -1; clickRequired = false;
    }
    void key(int key, int action, int modifiers) {
        boolean alt = key == GLFW_KEY_LEFT_ALT || key == GLFW_KEY_RIGHT_ALT;
        if (action == GLFW_REPEAT) return;
        if (!focused || iconified) { held.clear(); return; }
        if (alt) {
            if (action == GLFW_PRESS) {
                boolean alone = altKey == -1 && held.isEmpty() && (modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SHIFT | GLFW_MOD_SUPER)) == 0;
                boolean wasCaptured = captured;
                release(); altCandidate = alone; altWasCaptured = wasCaptured; altKey = key;
            } else if (action == GLFW_RELEASE && altKey == key) {
                boolean mayCapture = altCandidate && !altWasCaptured && !clickRequired;
                altCandidate = false; altKey = -1;
                if (mayCapture) capture();
            }
            return;
        }
        if (action == GLFW_RELEASE) { held.remove(key); return; }
        if (action != GLFW_PRESS) return;
        if (altKey != -1 || (modifiers & GLFW_MOD_ALT) != 0) { altCandidate = false; held.clear(); return; }
        if (key == GLFW_KEY_ESCAPE) {
            if (captured) release(); else closeRequested = true;
        } else if (key == GLFW_KEY_R) {
            orbit = ViewerOrbitCameraState.forBounds(bounds); free = ViewerFreeCameraState.from(orbit.pose()); release(); clickRequired = true;
        } else if (key == GLFW_KEY_O) {
            release(); orbitMode = !orbitMode;
            // Return to the saved orbit pose; never convert a distant free eye into a giant orbit radius.
            if (!orbitMode) free = ViewerFreeCameraState.from(orbit.pose());
            clickRequired = true;
        } else if (captured) held.add(key);
    }
    void mouseButton(int button, int action, double x, double y) {
        if (!focused || iconified) return;
        if (action == GLFW_PRESS && altKey != -1) { altCandidate = false; return; }
        if (!orbitMode) {
            if (!captured && button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) capture();
            return;
        }
        if (action == GLFW_PRESS && (button == GLFW_MOUSE_BUTTON_LEFT || button == GLFW_MOUSE_BUTTON_RIGHT)) {
            dragButton = button; pressX = lastX = x; pressY = lastY = y; rotating = false;
        } else if (action == GLFW_RELEASE && button == dragButton) dragButton = -1;
    }
    void cursor(double x, double y, int height) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !focused || iconified) return;
        if (captured) {
            if (cursorReady) free = free.turn(x - lastX, y - lastY);
            lastX = x; lastY = y; cursorReady = true;
        } else if (orbitMode && dragButton != -1) {
            if (dragButton == GLFW_MOUSE_BUTTON_LEFT) {
                if (!rotating) {
                    if (!GlfwViewerOrbitController.exceedsClickThreshold(pressX, pressY, x, y)) return;
                    rotating = true;
                }
                orbit = orbit.rotate(GlfwViewerOrbitController.yawDeltaFor(x - lastX), GlfwViewerOrbitController.pitchDeltaFor(y - lastY));
            } else orbit = orbit.panPixels(x - lastX, y - lastY, Math.max(1, height));
            lastX = x; lastY = y;
        }
    }
    void scroll(double steps) {
        if (!focused || iconified || !Double.isFinite(steps)) return;
        if (altKey != -1) { altCandidate = false; return; }
        if (captured) free = free.scroll(steps);
        else if (orbitMode) orbit = orbit.zoomBy(Math.pow(1.12, -Math.clamp(steps, -32, 32)));
    }
    void update(double seconds) {
        if (!captured || !focused || iconified) return;
        free = free.move(axis(GLFW_KEY_W, GLFW_KEY_S), axis(GLFW_KEY_D, GLFW_KEY_A),
                (held.contains(GLFW_KEY_SPACE) ? 1 : 0) - (held.contains(GLFW_KEY_LEFT_SHIFT) || held.contains(GLFW_KEY_RIGHT_SHIFT) ? 1 : 0),
                held.contains(GLFW_KEY_LEFT_CONTROL) || held.contains(GLFW_KEY_RIGHT_CONTROL), seconds);
    }
    private int axis(int positive, int negative) { return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0); }
    boolean captured() { return captured; }
    boolean orbitMode() { return orbitMode; }
    boolean closeRequested() { return closeRequested; }
    double speed() { return free.speed(); }
    ViewerOrbitCameraState orbit() { return orbit; }
    ViewerOrbitCameraState.CameraPose pose() { return orbitMode ? orbit.pose() : free.pose(); }
    String controls() {
        if (orbitMode) return "环绕查看 · 左拖旋转 / 右拖平移 / 滚轮缩放 · O 自由视角 · R 复位 · Esc 关闭";
        return captured ? "自由视角 · WASD 移动 · Space / Shift 升降 · Ctrl 加速 · 滚轮调速 · Alt / Esc 释放 · R 复位 · O 环绕"
                : "鼠标已释放 · 点击画面进入自由视角 · O 环绕查看 · R 复位 · Esc 关闭";
    }
}
