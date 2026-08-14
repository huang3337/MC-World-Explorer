package com.mcworldexplorer.viewer.lwjgl;

import org.joml.Matrix4f;
import org.joml.Vector3f;

final class ViewerCameraMatrices {
    private ViewerCameraMatrices() {
    }

    static Matrix4f viewProjection(ViewerOrbitCameraState camera, int width, int height) {
        if (camera == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("camera and positive viewport dimensions are required");
        }
        ViewerOrbitCameraState.CameraPose pose = camera.pose();
        Matrix4f projection = new Matrix4f().perspective(
                (float) ViewerOrbitCameraState.VERTICAL_FOV_RADIANS,
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
