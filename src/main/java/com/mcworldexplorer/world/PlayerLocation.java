package com.mcworldexplorer.world;

import java.util.Objects;
import java.util.UUID;

public record PlayerLocation(
        UUID uuid,
        String displayName,
        String dimensionId,
        double x,
        double y,
        double z,
        long modifiedTime) {

    public PlayerLocation {
        Objects.requireNonNull(uuid, "uuid");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("player display name must not be blank");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("player dimension must not be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("player coordinates must be finite");
        }
        if (modifiedTime < 0) {
            throw new IllegalArgumentException("player modified time must not be negative");
        }
    }

    public String shortUuid() {
        return uuid.toString().substring(0, 8);
    }
}
