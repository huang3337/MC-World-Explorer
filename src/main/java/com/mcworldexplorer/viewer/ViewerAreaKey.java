package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.nio.file.Path;
import java.util.Objects;

public record ViewerAreaKey(
        Path world,
        String dimensionId,
        ChunkRectangle target,
        String renderVersion) {
    public ViewerAreaKey {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(target, "target");
        world = world.toAbsolutePath().normalize();
        dimensionId = WorldDimension.normalizeId(dimensionId);
        if (renderVersion == null || renderVersion.isBlank()) {
            throw new IllegalArgumentException("renderVersion must not be blank");
        }
        renderVersion = renderVersion.trim();
    }
}
