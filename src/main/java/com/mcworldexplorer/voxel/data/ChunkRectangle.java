package com.mcworldexplorer.voxel.data;

import java.util.ArrayList;
import java.util.List;

public record ChunkRectangle(int minX, int minZ, int maxX, int maxZ) {
    public static final int MAX_TARGET_SIZE = 8;

    public ChunkRectangle {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("chunk rectangle bounds are reversed");
        }
    }

    public static ChunkRectangle target(int minX, int minZ, int maxX, int maxZ) {
        ChunkRectangle rectangle = new ChunkRectangle(minX, minZ, maxX, maxZ);
        if (rectangle.width() > MAX_TARGET_SIZE || rectangle.depth() > MAX_TARGET_SIZE) {
            throw new IllegalArgumentException("target chunk rectangle must not exceed 8x8");
        }
        return rectangle;
    }

    public static ChunkRectangle centered(
            int centerX,
            int centerZ,
            int width,
            int depth) {
        if (width < 1 || width > MAX_TARGET_SIZE || depth < 1 || depth > MAX_TARGET_SIZE) {
            throw new IllegalArgumentException("target width and depth must be between 1 and 8");
        }
        int minX = Math.subtractExact(centerX, (width - 1) / 2);
        int minZ = Math.subtractExact(centerZ, (depth - 1) / 2);
        return target(
                minX,
                minZ,
                Math.addExact(minX, width - 1),
                Math.addExact(minZ, depth - 1));
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
