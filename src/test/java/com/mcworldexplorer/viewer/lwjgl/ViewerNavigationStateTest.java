package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class ViewerNavigationStateTest {
    private static ViewerNavigationState state() {
        var s = new ViewerNavigationState(new MeshBounds(0, -64, 0, 48, 120, 48)); s.focus(true); return s;
    }
    private static void click(ViewerNavigationState s) { s.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 100, 100); }
    private static void press(ViewerNavigationState s, int key) { s.key(key, GLFW_PRESS, 0); }
    private static void tap(ViewerNavigationState s, int key) { press(s, key); s.key(key, GLFW_RELEASE, 0); }

    @Test void opensReleasedAndOnlyMovesWhileCaptured() {
        var s = state(); var original = s.pose(); assertFalse(s.captured());
        press(s, GLFW_KEY_W); s.update(1); s.cursor(1, 2, 800); assertEquals(original, s.pose());
        click(s); assertTrue(s.captured()); press(s, GLFW_KEY_W); s.update(.05); assertNotEquals(original, s.pose());
        tap(s, GLFW_KEY_ESCAPE); var released = s.pose(); assertFalse(s.captured()); assertFalse(s.closeRequested());
        s.update(1); s.cursor(999, 999, 800); assertEquals(released, s.pose());
        tap(s, GLFW_KEY_ESCAPE); assertTrue(s.closeRequested());
    }
    @Test void bothAltKeysToggleOnceAndIgnoreRepeat() {
        for (int key : new int[]{GLFW_KEY_LEFT_ALT, GLFW_KEY_RIGHT_ALT}) {
            var s = state(); click(s); press(s, key); assertFalse(s.captured());
            s.key(key, GLFW_REPEAT, GLFW_MOD_ALT); s.key(key, GLFW_RELEASE, 0); assertFalse(s.captured());
            tap(s, key); assertTrue(s.captured());
        }
    }
    @Test void altChordsFocusLossAndMinimizeRequireExplicitClickAndClearKeys() {
        var s = state(); click(s); press(s, GLFW_KEY_W); press(s, GLFW_KEY_LEFT_ALT);
        s.key(GLFW_KEY_TAB, GLFW_PRESS, GLFW_MOD_ALT); s.focus(false); s.key(GLFW_KEY_W, GLFW_RELEASE, 0);
        s.focus(true); s.key(GLFW_KEY_LEFT_ALT, GLFW_RELEASE, 0); tap(s, GLFW_KEY_LEFT_ALT);
        var stopped = s.pose(); s.update(1); assertFalse(s.captured()); assertEquals(stopped, s.pose());
        click(s); s.update(.1); assertEquals(stopped, s.pose());
        press(s, GLFW_KEY_D); s.iconify(true); s.iconify(false); s.update(.1); assertFalse(s.captured()); assertEquals(stopped, s.pose());
        click(s); s.update(.1); assertEquals(stopped, s.pose());
        tap(s, GLFW_KEY_LEFT_ALT); press(s, GLFW_KEY_LEFT_ALT); s.key(GLFW_KEY_F4, GLFW_PRESS, GLFW_MOD_ALT);
        s.key(GLFW_KEY_LEFT_ALT, GLFW_RELEASE, 0); assertFalse(s.captured());
    }
    @Test void recaptureDiscardsOldMouseCoordinatesAndPitchNeverFlips() {
        var s = state(); click(s); var original = s.pose();
        s.cursor(10000, 5000, 800); assertEquals(original, s.pose());
        s.cursor(10040, 5020, 800); assertNotEquals(original, s.pose());
        tap(s, GLFW_KEY_ESCAPE); click(s); var fresh = s.pose(); s.cursor(-10000, -20000, 800); assertEquals(fresh, s.pose());
        s.cursor(-10000, 1e8, 800); var p = s.pose(); assertTrue(Double.isFinite(p.focusY())); assertTrue(p.eyeY() > p.focusY());
    }
    @Test void oppositeKeysAndReleaseRemoveMotionAndSceneResetClearsEverything() {
        var s = state(); click(s); var start = s.pose();
        press(s, GLFW_KEY_W); press(s, GLFW_KEY_S); s.update(.1); assertEquals(start, s.pose());
        s.key(GLFW_KEY_S, GLFW_RELEASE, 0); s.update(.1); assertNotEquals(start, s.pose());
        s.reset(new MeshBounds(-32, -128, -32, 0, -64, 0)); var reset = s.pose(); s.update(1);
        assertFalse(s.captured()); assertEquals(reset, s.pose()); assertNotEquals(start, reset);
        click(s); s.update(.1); assertEquals(reset, s.pose());
    }
    @Test void orbitEntryKeepsLegacyDragAndResetIsSafe() {
        var s = state(); var initial = s.pose(); var initialOrbit = s.orbit().pose(); tap(s, GLFW_KEY_O); assertTrue(s.orbitMode());
        s.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0, 0); s.cursor(4, 0, 800); assertEquals(initialOrbit, s.pose());
        s.cursor(40, 20, 800); assertNotEquals(initialOrbit, s.pose()); assertFalse(s.captured());
        tap(s, GLFW_KEY_R); assertEquals(initialOrbit, s.pose()); tap(s, GLFW_KEY_O); assertFalse(s.orbitMode());
        click(s); press(s, GLFW_KEY_SPACE); s.update(.1); assertTrue(s.pose().eyeY() > initial.eyeY());
        tap(s, GLFW_KEY_R); assertEquals(initial, s.pose()); assertFalse(s.captured());
    }
}
