package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.mesh.MeshBounds;

import java.util.Objects;

public final class ViewerOrbitCameraState {
    static final double VERTICAL_FOV_RADIANS = Math.toRadians(45);
    private static final double DEFAULT_YAW = Math.toRadians(45);
    private static final double DEFAULT_PITCH = Math.toRadians(30);
    private static final double MIN_PITCH = Math.toRadians(-85);
    private static final double MAX_PITCH = Math.toRadians(85);

    private final double initialFocusX;
    private final double initialFocusY;
    private final double initialFocusZ;
    private final double focusX;
    private final double focusY;
    private final double focusZ;
    private final double yaw;
    private final double pitch;
    private final double distance;
    private final double defaultDistance;
    private final double minDistance;
    private final double maxDistance;
    private final double maxTargetRadius;

    private ViewerOrbitCameraState(
            double initialFocusX,
            double initialFocusY,
            double initialFocusZ,
            double focusX,
            double focusY,
            double focusZ,
            double yaw,
            double pitch,
            double distance,
            double defaultDistance,
            double minDistance,
            double maxDistance,
            double maxTargetRadius) {
        this.initialFocusX = initialFocusX;
        this.initialFocusY = initialFocusY;
        this.initialFocusZ = initialFocusZ;
        this.focusX = focusX;
        this.focusY = focusY;
        this.focusZ = focusZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.distance = distance;
        this.defaultDistance = defaultDistance;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.maxTargetRadius = maxTargetRadius;
    }

    public static ViewerOrbitCameraState forBounds(MeshBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        double diagonal = Math.max(1.0, bounds.diagonal());
        double defaultDistance = Math.max(32.0, diagonal * 1.5);
        return new ViewerOrbitCameraState(
                bounds.centerX(), bounds.centerY(), bounds.centerZ(),
                bounds.centerX(), bounds.centerY(), bounds.centerZ(),
                DEFAULT_YAW, DEFAULT_PITCH, defaultDistance, defaultDistance,
                Math.max(0.5, diagonal * 0.02), Math.max(64.0, diagonal * 8.0),
                diagonal);
    }

    public ViewerOrbitCameraState rotate(double yawDelta, double pitchDelta) {
        if (!Double.isFinite(yawDelta) || !Double.isFinite(pitchDelta)) {
            throw new IllegalArgumentException("camera rotation must be finite");
        }
        return new ViewerOrbitCameraState(
                initialFocusX, initialFocusY, initialFocusZ,
                focusX, focusY, focusZ, yaw + yawDelta,
                clamp(pitch + pitchDelta, MIN_PITCH, MAX_PITCH), distance,
                defaultDistance, minDistance, maxDistance, maxTargetRadius);
    }

    public ViewerOrbitCameraState zoomBy(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0) {
            throw new IllegalArgumentException("zoom multiplier must be positive and finite");
        }
        return new ViewerOrbitCameraState(
                initialFocusX, initialFocusY, initialFocusZ,
                focusX, focusY, focusZ, yaw, pitch,
                clamp(distance * multiplier, minDistance, maxDistance),
                defaultDistance, minDistance, maxDistance, maxTargetRadius);
    }

    public ViewerOrbitCameraState panPixels(
            double deltaX,
            double deltaY,
            double viewportHeight) {
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)
                || !Double.isFinite(viewportHeight) || viewportHeight <= 0) {
            throw new IllegalArgumentException(
                    "camera pan and viewport height must be finite and valid");
        }
        double worldPerPixel = 2.0 * distance
                * Math.tan(VERTICAL_FOV_RADIANS / 2.0) / viewportHeight;
        double rightX = Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double upX = -Math.sin(yaw) * Math.sin(pitch);
        double upY = Math.cos(pitch);
        double upZ = -Math.cos(yaw) * Math.sin(pitch);
        double nextX = focusX + (-rightX * deltaX + upX * deltaY) * worldPerPixel;
        double nextY = focusY + upY * deltaY * worldPerPixel;
        double nextZ = focusZ + (-rightZ * deltaX + upZ * deltaY) * worldPerPixel;
        double offsetX = nextX - initialFocusX;
        double offsetY = nextY - initialFocusY;
        double offsetZ = nextZ - initialFocusZ;
        double offsetLength = Math.sqrt(
                offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        if (offsetLength > maxTargetRadius) {
            double scale = maxTargetRadius / offsetLength;
            nextX = initialFocusX + offsetX * scale;
            nextY = initialFocusY + offsetY * scale;
            nextZ = initialFocusZ + offsetZ * scale;
        }
        return new ViewerOrbitCameraState(
                initialFocusX, initialFocusY, initialFocusZ,
                nextX, nextY, nextZ, yaw, pitch, distance,
                defaultDistance, minDistance, maxDistance, maxTargetRadius);
    }

    public ViewerOrbitCameraState reset() {
        return new ViewerOrbitCameraState(
                initialFocusX, initialFocusY, initialFocusZ,
                initialFocusX, initialFocusY, initialFocusZ,
                DEFAULT_YAW, DEFAULT_PITCH, defaultDistance, defaultDistance,
                minDistance, maxDistance, maxTargetRadius);
    }

    public CameraPose pose() {
        double horizontal = Math.cos(pitch) * distance;
        return new CameraPose(
                focusX + Math.sin(yaw) * horizontal,
                focusY + Math.sin(pitch) * distance,
                focusZ + Math.cos(yaw) * horizontal,
                focusX, focusY, focusZ);
    }

    public double distance() {
        return distance;
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
