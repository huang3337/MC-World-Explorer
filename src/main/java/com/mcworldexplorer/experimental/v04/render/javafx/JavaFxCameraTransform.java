package com.mcworldexplorer.experimental.v04.render.javafx;

import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;
import javafx.geometry.Point3D;
import javafx.scene.transform.Affine;

public final class JavaFxCameraTransform {
    private JavaFxCameraTransform() {
    }

    public static Affine viewTransform(OrbitCameraState state) {
        Affine transform = new Affine();
        update(transform, state);
        return transform;
    }

    public static void update(Affine transform, OrbitCameraState state) {
        if (transform == null || state == null) {
            throw new IllegalArgumentException("transform and state must not be null");
        }
        OrbitCameraState.CameraPose pose = state.pose();
        Point3D eye = new Point3D(pose.eyeX(), pose.eyeY(), pose.eyeZ());
        Point3D forward = new Point3D(
                pose.focusX() - pose.eyeX(),
                pose.focusY() - pose.eyeY(),
                pose.focusZ() - pose.eyeZ()).normalize();
        Point3D right = forward.crossProduct(Point3D.ZERO.add(0, 1, 0)).normalize();
        Point3D down = forward.crossProduct(right).normalize();
        transform.setToTransform(
                right.getX(), right.getY(), right.getZ(), -right.dotProduct(eye),
                down.getX(), down.getY(), down.getZ(), -down.dotProduct(eye),
                forward.getX(), forward.getY(), forward.getZ(), -forward.dotProduct(eye));
    }
}
