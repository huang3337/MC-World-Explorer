package com.mcworldexplorer.viewer.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlfwViewerOrbitControllerTest {
    @Test
    void fourPixelMovementRemainsAClickCandidate() {
        assertFalse(GlfwViewerOrbitController.exceedsClickThreshold(10, 20, 14, 20));
        assertTrue(GlfwViewerOrbitController.exceedsClickThreshold(10, 20, 14.1, 20));
    }

    @Test
    void bothRotationAxesUseTheConfirmedReversedDirection() {
        assertTrue(GlfwViewerOrbitController.yawDeltaFor(10) < 0);
        assertTrue(GlfwViewerOrbitController.pitchDeltaFor(10) > 0);
    }
}
