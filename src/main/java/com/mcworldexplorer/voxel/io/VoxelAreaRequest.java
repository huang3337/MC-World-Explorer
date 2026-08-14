package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.nio.file.Path;
import java.util.Objects;

public record VoxelAreaRequest(Path world, String dimensionId, ChunkRectangle target) {
    public VoxelAreaRequest {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(target, "target");
        world = world.toAbsolutePath().normalize();
        dimensionId = WorldDimension.normalizeId(dimensionId);
        if (target.width() > ChunkRectangle.MAX_TARGET_SIZE
                || target.depth() > ChunkRectangle.MAX_TARGET_SIZE) {
            throw new IllegalArgumentException("target chunk rectangle must not exceed 8x8");
        }
    }
}
