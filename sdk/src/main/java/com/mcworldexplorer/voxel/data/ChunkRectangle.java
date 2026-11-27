package com.mcworldexplorer.voxel.data;

import java.util.ArrayList;
import java.util.List;

public record ChunkRectangle(int minX, int minZ, int maxX, int maxZ) {
    public static final int MAX_TARGET_SIZE = 32;
    public static final int MAX_TARGET_CHUNKS = 256;

    public ChunkRectangle {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("chunk rectangle bounds are reversed");
        }
    }

    public static ChunkRectangle target(int minX, int minZ, int maxX, int maxZ) {
        ChunkRectangle rectangle = new ChunkRectangle(minX, minZ, maxX, maxZ);
        if (!rectangle.isSupportedTarget()) {
            throw new IllegalArgumentException("target must fit within 32 chunks per side and 256 chunks total");
        }
        return rectangle;
    }

    public static ChunkRectangle centered(
            int centerX,
            int centerZ,
            int width,
            int depth) {
        if (width < 1 || width > MAX_TARGET_SIZE || depth < 1 || depth > MAX_TARGET_SIZE) {
            throw new IllegalArgumentException("target width and depth must be between 1 and 32");
        }
        int minX = Math.subtractExact(centerX, (width - 1) / 2);
        int minZ = Math.subtractExact(centerZ, (depth - 1) / 2);
        return target(
                minX,
                minZ,
                Math.addExact(minX, width - 1),
                Math.addExact(minZ, depth - 1));
    }

    public boolean isSupportedTarget() {
        long width = (long) maxX - minX + 1;
        long depth = (long) maxZ - minZ + 1;
        return width <= MAX_TARGET_SIZE && depth <= MAX_TARGET_SIZE
                && width * depth <= MAX_TARGET_CHUNKS
                && minX > Integer.MIN_VALUE && minZ > Integer.MIN_VALUE
                && maxX < Integer.MAX_VALUE && maxZ < Integer.MAX_VALUE;
    }

    public int width() {
        return Math.addExact(Math.subtractExact(maxX, minX), 1);
    }

    public int depth() {
        return Math.addExact(Math.subtractExact(maxZ, minZ), 1);
    }

    public boolean contains(ChunkCoordinate coordinate) {
        return coordinate.x() >= minX && coordinate.x() <= maxX
                && coordinate.z() >= minZ && coordinate.z() <= maxZ;
    }

    public ChunkRectangle expanded(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("expansion must not be negative");
        }
        return new ChunkRectangle(
                Math.subtractExact(minX, amount),
                Math.subtractExact(minZ, amount),
                Math.addExact(maxX, amount),
                Math.addExact(maxZ, amount));
    }

    public List<ChunkCoordinate> coordinates() {
        List<ChunkCoordinate> coordinates = new ArrayList<>(Math.multiplyExact(width(), depth()));
        for (int z = minZ; ; z++) {
            for (int x = minX; ; x++) {
                coordinates.add(new ChunkCoordinate(x, z));
                if (x == maxX) {
                    break;
                }
            }
            if (z == maxZ) {
                break;
            }
        }
        return List.copyOf(coordinates);
    }
}
