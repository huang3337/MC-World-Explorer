package com.mcworldexplorer.experimental.v04.render.lwjgl;

import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class LwjglCameraMatrices {
    private LwjglCameraMatrices() {
    }

    public static Matrix4f viewProjection(
            OrbitCameraState camera,
            int width,
            int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
        OrbitCameraState.CameraPose pose = camera.pose();
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(45),
                (float) width / height,
                0.1f,
                (float) Math.max(10_000, camera.distance() * 20));
        Matrix4f view = new Matrix4f().lookAt(
                new Vector3f((float) pose.eyeX(), (float) pose.eyeY(), (float) pose.eyeZ()),
                new Vector3f((float) pose.focusX(), (float) pose.focusY(), (float) pose.focusZ()),
                new Vector3f(0, 1, 0));
        return projection.mul(view);
    }
}
