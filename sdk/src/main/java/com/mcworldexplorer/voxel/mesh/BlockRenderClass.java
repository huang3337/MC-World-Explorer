package com.mcworldexplorer.voxel.mesh;

import java.util.Objects;

public record BlockRenderClass(
        Kind kind,
        RenderLayer layer,
        int rgb,
        float alpha) {
    public BlockRenderClass {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.AIR && layer != null) {
            throw new IllegalArgumentException("air must not have a render layer");
        }
        if (kind != Kind.AIR && layer == null) {
            throw new IllegalArgumentException("renderable blocks require a layer");
        }
        if (!Float.isFinite(alpha) || alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException("alpha must be between 0 and 1");
        }
        rgb &= 0xFFFFFF;
    }

    public boolean isAir() {
        return kind == Kind.AIR;
    }

    public boolean isFluid() {
        return kind == Kind.FLUID;
    }

    public enum Kind {
        AIR,
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        FLUID,
        FALLBACK
    }
}
