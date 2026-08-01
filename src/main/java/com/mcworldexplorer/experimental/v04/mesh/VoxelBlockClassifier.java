package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;

import java.util.Set;

public final class VoxelBlockClassifier {
    private static final Set<String> AIR = Set.of(
            "minecraft:air",
            "minecraft:cave_air",
            "minecraft:void_air");

    private VoxelBlockClassifier() {
    }

    public static VoxelBlockKind classify(VoxelBlockState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (AIR.contains(state.name())) {
            return VoxelBlockKind.AIR;
        }
        if ("minecraft:water".equals(state.name())) {
            return VoxelBlockKind.WATER;
        }
        if ("minecraft:lava".equals(state.name())) {
            return VoxelBlockKind.LAVA;
        }
        return VoxelBlockKind.SOLID;
    }
}
