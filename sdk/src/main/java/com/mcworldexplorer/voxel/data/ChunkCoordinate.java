package com.mcworldexplorer.voxel.data;

public record ChunkCoordinate(int x, int z) implements Comparable<ChunkCoordinate> {
    public ChunkCoordinate offset(int deltaX, int deltaZ) {
        return new ChunkCoordinate(Math.addExact(x, deltaX), Math.addExact(z, deltaZ));
    }

    @Override
    public int compareTo(ChunkCoordinate other) {
        int byZ = Integer.compare(z, other.z);
        return byZ != 0 ? byZ : Integer.compare(x, other.x);
    }
}
