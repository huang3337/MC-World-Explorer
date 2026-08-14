package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.nio.file.Path;
import java.util.Objects;

public record ViewerRequest(
        Path world,
        String worldName,
        String dimensionId,
        String dimensionName,
        ChunkRectangle target,
        ViewerPerformanceProfile profile,
        ViewerMapReturnState returnState) {
    public ViewerRequest {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(returnState, "returnState");
        world = world.toAbsolutePath().normalize();
        worldName = requireText(worldName, "worldName");
        dimensionId = requireText(dimensionId, "dimensionId");
        dimensionName = requireText(dimensionName, "dimensionName");
        if (target.width() != target.depth()) {
            throw new IllegalArgumentException("viewer target must be square");
        }
        ViewerRangePreset preset = ViewerRangePreset.fromChunks(target.width());
        if (!profile.allows(preset)) {
            throw new IllegalArgumentException(
                    profile.displayName() + " does not allow " + preset);
        }
    }

    public ViewerRangePreset rangePreset() {
        return ViewerRangePreset.fromChunks(target.width());
    }

    public ViewerAreaKey areaKey(String renderVersion) {
        return new ViewerAreaKey(world, dimensionId, target, renderVersion);
    }

    public long centerBlockX() {
        return Math.addExact(
                Math.multiplyExact((long) target.minX(), 16L),
                Math.multiplyExact((long) target.width(), 8L));
    }

    public long centerBlockZ() {
        return Math.addExact(
                Math.multiplyExact((long) target.minZ(), 16L),
                Math.multiplyExact((long) target.depth(), 8L));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
