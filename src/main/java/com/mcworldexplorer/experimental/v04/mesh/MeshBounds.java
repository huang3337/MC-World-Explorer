package com.mcworldexplorer.experimental.v04.mesh;

public record MeshBounds(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ) {
    public MeshBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("invalid mesh bounds");
        }
    }

    public double centerX() {
        return (minX + maxX) / 2.0;
    }

    public double centerY() {
        return (minY + maxY) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2.0;
    }

    public double diagonal() {
        double x = maxX - minX;
        double y = maxY - minY;
        double z = maxZ - minZ;
        return Math.sqrt(x * x + y * y + z * z);
    }
}
