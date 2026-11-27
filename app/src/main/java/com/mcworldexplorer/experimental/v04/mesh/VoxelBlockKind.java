package com.mcworldexplorer.experimental.v04.mesh;

public enum VoxelBlockKind {
    AIR,
    SOLID,
    WATER,
    LAVA;

    public boolean isFluid() {
        return this == WATER || this == LAVA;
    }
}
