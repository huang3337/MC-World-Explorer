package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerOrbitCameraStateTest {
    @Test
    void resetRestoresDefaultPoseAfterRotationAndZoom() {
        ViewerOrbitCameraState initial = ViewerOrbitCameraState.forBounds(
                new MeshBounds(-10, -64, -20, 40, 320, 60));
        ViewerOrbitCameraState changed = initial.rotate(0.8, -0.4).zoomBy(0.25);

        assertNotEquals(initial.pose(), changed.pose());
        assertEquals(initial.pose(), changed.reset().pose());
        assertEquals(initial.distance(), changed.reset().distance());
    }

    @Test
    void resetRestoresOriginalFocusAfterPanning() {
        ViewerOrbitCameraState initial = ViewerOrbitCameraState.forBounds(
                new MeshBounds(-10, -64, -20, 40, 320, 60));
        ViewerOrbitCameraState panned = initial.panPixels(120, -80, 800);

        assertNotEquals(initial.pose(), panned.pose());
        assertEquals(initial.pose(), panned.reset().pose());
    }

    @Test
    void screenSpacePanScalesWithCameraDistance() {
        ViewerOrbitCameraState near = ViewerOrbitCameraState.forBounds(
                new MeshBounds(0, 0, 0, 16, 16, 16));
        ViewerOrbitCameraState far = near.zoomBy(2.0);

        double nearMovement = focusMovement(near, near.panPixels(40, 0, 800));
        double farMovement = focusMovement(far, far.panPixels(40, 0, 800));

        assertEquals(nearMovement * 2.0, farMovement, 1.0e-9);
    }

    @Test
    void panningCannotMoveFocusBeyondSceneRadius() {
        MeshBounds bounds = new MeshBounds(0, 0, 0, 16, 32, 16);
        ViewerOrbitCameraState initial = ViewerOrbitCameraState.forBounds(bounds);
        ViewerOrbitCameraState panned = initial.panPixels(1_000_000, 1_000_000, 100);

        assertTrue(focusMovement(initial, panned) <= bounds.diagonal() + 1.0e-9);
    }

    @Test
    void rejectsInvalidCameraInput() {
        ViewerOrbitCameraState state = ViewerOrbitCameraState.forBounds(
                new MeshBounds(0, 0, 0, 1, 1, 1));

        assertThrows(IllegalArgumentException.class, () -> state.zoomBy(0));
        assertThrows(IllegalArgumentException.class, () -> state.rotate(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> state.panPixels(0, 0, 0));
    }

    private static double focusMovement(
            ViewerOrbitCameraState initial,
            ViewerOrbitCameraState changed) {
        ViewerOrbitCameraState.CameraPose first = initial.pose();
        ViewerOrbitCameraState.CameraPose second = changed.pose();
        double x = second.focusX() - first.focusX();
        double y = second.focusY() - first.focusY();
        double z = second.focusZ() - first.focusZ();
        return Math.sqrt(x * x + y * y + z * z);
    }
}
