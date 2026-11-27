package com.mcworldexplorer.experimental.v04.render.javafx;

import com.mcworldexplorer.experimental.v04.mesh.MeshBounds;
import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;
import javafx.geometry.Point3D;
import javafx.scene.transform.Affine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFxCameraTransformTest {
    @Test
    void mapsEyeToOriginAndFocusInFrontOfCamera() {
        OrbitCameraState state = OrbitCameraState.forBounds(
                new MeshBounds(-16, -64, 32, 0, 320, 48));
        OrbitCameraState.CameraPose pose = state.pose();

        Affine transform = JavaFxCameraTransform.viewTransform(state);
        Point3D eye = transform.transform(pose.eyeX(), pose.eyeY(), pose.eyeZ());
        Point3D focus = transform.transform(pose.focusX(), pose.focusY(), pose.focusZ());

        assertEquals(0, eye.getX(), 1.0e-9);
        assertEquals(0, eye.getY(), 1.0e-9);
        assertEquals(0, eye.getZ(), 1.0e-9);
        assertEquals(0, focus.getX(), 1.0e-9);
        assertEquals(0, focus.getY(), 1.0e-9);
        assertTrue(focus.getZ() > 0);
        assertEquals(state.distance(), focus.getZ(), 1.0e-9);

        Affine reused = new Affine();
        OrbitCameraState movedState = state.rotate(0.2, -0.1);
        OrbitCameraState.CameraPose moved = movedState.pose();
        JavaFxCameraTransform.update(reused, movedState);
        Point3D movedEye = reused.transform(moved.eyeX(), moved.eyeY(), moved.eyeZ());
        assertEquals(0, movedEye.magnitude(), 1.0e-9);
    }
}
