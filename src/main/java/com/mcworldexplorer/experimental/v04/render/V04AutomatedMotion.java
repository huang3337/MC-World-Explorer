package com.mcworldexplorer.experimental.v04.render;

public final class V04AutomatedMotion {
    private V04AutomatedMotion() {
    }

    public static OrbitCameraState stateAt(OrbitCameraState initial, double elapsedSeconds) {
        if (initial == null || !Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException("invalid automated motion input");
        }
        double yawDelta = elapsedSeconds * 0.45;
        double pitchDelta = Math.sin(elapsedSeconds * 0.65) * Math.toRadians(20);
        double zoomMultiplier = Math.exp(Math.sin(elapsedSeconds * 0.4) * 0.25);
        return initial.rotate(yawDelta, pitchDelta).zoomBy(zoomMultiplier);
    }
}
