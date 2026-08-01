package com.mcworldexplorer.experimental.v04.render;

import com.mcworldexplorer.experimental.v04.mesh.MeshBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V04AutomatedMotionTest {
    @Test
    void startsAtInitialStateAndProducesFiniteMotion() {
        OrbitCameraState initial = OrbitCameraState.forBounds(
                new MeshBounds(0, -64, 0, 16, 192, 16));

        OrbitCameraState start = V04AutomatedMotion.stateAt(initial, 0);
        OrbitCameraState moved = V04AutomatedMotion.stateAt(initial, 3.5);

        assertEquals(initial.yaw(), start.yaw());
        assertEquals(initial.pitch(), start.pitch());
        assertEquals(initial.distance(), start.distance());
        assertNotEquals(initial.yaw(), moved.yaw());
        assertTrue(Double.isFinite(moved.pose().eyeX()));
        assertTrue(Double.isFinite(moved.distance()));
    }

    @Test
    void rejectsInvalidTime() {
        OrbitCameraState initial = OrbitCameraState.forBounds(
                new MeshBounds(0, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> V04AutomatedMotion.stateAt(initial, -1));
    }
}
