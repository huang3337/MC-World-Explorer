package com.mcworldexplorer.experimental.v04.render;

import com.mcworldexplorer.experimental.v04.mesh.MeshBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbitCameraStateTest {
    @Test
    void centersOnBoundsAndResetsAfterMovement() {
        OrbitCameraState original = OrbitCameraState.forBounds(
                new MeshBounds(-16, -64, 32, 0, 320, 48));

        assertEquals(-8, original.focusX());
        assertEquals(128, original.focusY());
        assertEquals(40, original.focusZ());
        assertTrue(original.distance() >= 32);

        OrbitCameraState moved = original.rotate(0.5, 0.2).zoomBy(0.5);
        assertNotEquals(original.yaw(), moved.yaw());
        assertNotEquals(original.distance(), moved.distance());

        OrbitCameraState reset = moved.reset();
        assertEquals(original.yaw(), reset.yaw());
        assertEquals(original.pitch(), reset.pitch());
        assertEquals(original.distance(), reset.distance());
    }

    @Test
    void clampsPitchAndZoomWithoutProducingInvalidPose() {
        OrbitCameraState state = OrbitCameraState.forBounds(
                new MeshBounds(0, 0, 0, 16, 16, 16))
                .rotate(0, 100)
                .zoomBy(0.000001);

        assertTrue(state.pitch() < Math.PI / 2);
        assertTrue(state.distance() > 0);
        OrbitCameraState.CameraPose pose = state.pose();
        assertTrue(Double.isFinite(pose.eyeX()));
        assertTrue(Double.isFinite(pose.eyeY()));
        assertTrue(Double.isFinite(pose.eyeZ()));
    }
}
