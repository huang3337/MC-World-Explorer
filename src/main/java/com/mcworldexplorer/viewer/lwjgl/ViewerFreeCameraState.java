package com.mcworldexplorer.viewer.lwjgl;

/** Scene-relative coordinates keep large/negative world coordinates out of float matrices. */
record ViewerFreeCameraState(double x, double y, double z, double yaw, double pitch, double speed) {
    static final double DEFAULT_SPEED = 16;
    static final double MIN_SPEED = 1, MAX_SPEED = 128, MAX_STEP_SECONDS = .1;
    private static final double MAX_PITCH = Math.toRadians(89);

    ViewerFreeCameraState {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(speed)
                || speed < MIN_SPEED || speed > MAX_SPEED || Math.abs(pitch) > MAX_PITCH)
            throw new IllegalArgumentException("invalid free camera state");
    }
    static ViewerFreeCameraState from(ViewerOrbitCameraState.CameraPose pose) {
        double dx = pose.focusX() - pose.eyeX(), dy = pose.focusY() - pose.eyeY(), dz = pose.focusZ() - pose.eyeZ();
        return new ViewerFreeCameraState(pose.eyeX(), pose.eyeY(), pose.eyeZ(), Math.atan2(dx, -dz),
                Math.clamp(Math.atan2(dy, Math.hypot(dx, dz)), -MAX_PITCH, MAX_PITCH), DEFAULT_SPEED);
    }
    ViewerFreeCameraState turn(double dx, double dy) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return this;
        return new ViewerFreeCameraState(x, y, z, Math.IEEEremainder(yaw + dx * .0025, Math.PI * 2),
                Math.clamp(pitch - dy * .0025, -MAX_PITCH, MAX_PITCH), speed);
    }
    ViewerFreeCameraState scroll(double steps) {
        if (!Double.isFinite(steps)) return this;
        return new ViewerFreeCameraState(x, y, z, yaw, pitch,
                Math.clamp(speed * Math.pow(1.25, Math.clamp(steps, -32, 32)), MIN_SPEED, MAX_SPEED));
    }
    ViewerFreeCameraState move(double forward, double right, double up, boolean fast, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return this;
        double dx = Math.sin(yaw) * Math.cos(pitch) * forward + Math.cos(yaw) * right;
        double dy = Math.sin(pitch) * forward + up;
        double dz = -Math.cos(yaw) * Math.cos(pitch) * forward + Math.sin(yaw) * right;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9) return this;
        double amount = speed * (fast ? 4 : 1) * Math.min(seconds, MAX_STEP_SECONDS) / length;
        // Safety envelope only: no collision, streaming or implicit interpretation of unloaded space.
        return new ViewerFreeCameraState(Math.clamp(x + dx * amount, -1_000_000, 1_000_000),
                Math.clamp(y + dy * amount, -1_000_000, 1_000_000), Math.clamp(z + dz * amount, -1_000_000, 1_000_000), yaw, pitch, speed);
    }
    ViewerOrbitCameraState.CameraPose pose() {
        return new ViewerOrbitCameraState.CameraPose(x, y, z,
                x + Math.sin(yaw) * Math.cos(pitch), y + Math.sin(pitch), z - Math.cos(yaw) * Math.cos(pitch));
    }
}
