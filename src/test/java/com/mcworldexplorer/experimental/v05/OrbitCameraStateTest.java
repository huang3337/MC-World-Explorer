package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.voxel.mesh.MeshBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrbitCameraStateTest {
    @Test
    void framesBoundsAndSupportsRotateZoomReset() {
        OrbitCameraState initial = OrbitCameraState.forBounds(
                new MeshBounds(0, -64, 0, 48, 80, 48));
        OrbitCameraState rotated = initial.rotate(0.5, 0.2);
        OrbitCameraState zoomed = rotated.zoomBy(0.5);

        assertNotEquals(initial.pose(), rotated.pose());
        assertEquals(rotated.distance() * 0.5, zoomed.distance(), 0.001);
        assertEquals(initial.pose(), zoomed.reset().pose());
        assertThrows(IllegalArgumentException.class, () -> initial.zoomBy(0));
    }
}
