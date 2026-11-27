package com.mcworldexplorer.experimental.v04.render;

import com.mcworldexplorer.experimental.v04.mesh.MeshBounds;

public final class OrbitCameraState {
    private static final double DEFAULT_YAW = Math.toRadians(45);
    private static final double DEFAULT_PITCH = Math.toRadians(30);
    private static final double MIN_PITCH = Math.toRadians(-85);
    private static final double MAX_PITCH = Math.toRadians(85);

    private final double focusX;
    private final double focusY;
    private final double focusZ;
    private final double yaw;
    private final double pitch;
    private final double distance;
    private final double defaultDistance;
    private final double minDistance;
    private final double maxDistance;

    private OrbitCameraState(
            double focusX,
            double focusY,
            double focusZ,
            double yaw,
            double pitch,
            double distance,
            double defaultDistance,
            double minDistance,
            double maxDistance) {
        this.focusX = focusX;
        this.focusY = focusY;
        this.focusZ = focusZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.distance = distance;
        this.defaultDistance = defaultDistance;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
    }

    public static OrbitCameraState forBounds(MeshBounds bounds) {
        double diagonal = Math.max(1.0, bounds.diagonal());
        double defaultDistance = Math.max(32.0, diagonal * 1.5);
        return new OrbitCameraState(
                bounds.centerX(),
                bounds.centerY(),
                bounds.centerZ(),
                DEFAULT_YAW,
                DEFAULT_PITCH,
                defaultDistance,
                defaultDistance,
                Math.max(0.5, diagonal * 0.02),
                Math.max(64.0, diagonal * 8.0));
    }

    public OrbitCameraState rotate(double yawDelta, double pitchDelta) {
        return new OrbitCameraState(
                focusX,
                focusY,
                focusZ,
                yaw + yawDelta,
                clamp(pitch + pitchDelta, MIN_PITCH, MAX_PITCH),
                distance,
                defaultDistance,
                minDistance,
                maxDistance);
    }

    public OrbitCameraState zoomBy(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0) {
            throw new IllegalArgumentException("zoom multiplier must be positive and finite");
        }
        return new OrbitCameraState(
                focusX,
                focusY,
                focusZ,
                yaw,
                pitch,
                clamp(distance * multiplier, minDistance, maxDistance),
                defaultDistance,
                minDistance,
                maxDistance);
    }

    public OrbitCameraState reset() {
        return new OrbitCameraState(
                focusX,
                focusY,
                focusZ,
                DEFAULT_YAW,
                DEFAULT_PITCH,
                defaultDistance,
                defaultDistance,
                minDistance,
                maxDistance);
    }

    public CameraPose pose() {
        double horizontal = Math.cos(pitch) * distance;
        return new CameraPose(
                focusX + Math.sin(yaw) * horizontal,
                focusY + Math.sin(pitch) * distance,
                focusZ + Math.cos(yaw) * horizontal,
                focusX,
                focusY,
                focusZ);
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public double distance() {
        return distance;
    }

    public double focusX() {
        return focusX;
    }

    public double focusY() {
        return focusY;
    }

    public double focusZ() {
        return focusZ;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record CameraPose(
            double eyeX,
            double eyeY,
            double eyeZ,
            double focusX,
            double focusY,
            double focusZ) {
    }
}
