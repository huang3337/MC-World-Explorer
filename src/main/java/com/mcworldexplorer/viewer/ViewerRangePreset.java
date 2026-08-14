package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.data.ChunkRectangle;

public enum ViewerRangePreset {
    ONE(1),
    THREE(3),
    FIVE(5),
    EIGHT(8);

    private final int chunks;

    ViewerRangePreset(int chunks) {
        this.chunks = chunks;
    }

    public int chunks() {
        return chunks;
    }

    public int chunkCount() {
        return Math.multiplyExact(chunks, chunks);
    }

    public ChunkRectangle atWorld(double worldX, double worldZ) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ)) {
            throw new IllegalArgumentException("world coordinates must be finite");
        }
        int chunkX = floorToChunk(worldX);
        int chunkZ = floorToChunk(worldZ);
        return ChunkRectangle.centered(chunkX, chunkZ, chunks, chunks);
    }

    public static ViewerRangePreset fromChunks(int chunks) {
        for (ViewerRangePreset preset : values()) {
            if (preset.chunks == chunks) {
                return preset;
            }
        }
        throw new IllegalArgumentException("unsupported viewer range: " + chunks + "x" + chunks);
    }

    @Override
    public String toString() {
        return chunks + " x " + chunks;
    }

    private static int floorToChunk(double coordinate) {
        double chunk = Math.floor(coordinate / 16.0);
        if (chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("world coordinate is outside the supported range");
        }
        return (int) chunk;
    }
}
