package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.VoxelChunk;

import java.util.Objects;

public record DecodedChunk(ChunkStorageLayout layout, VoxelChunk chunk) {
    public DecodedChunk {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(chunk, "chunk");
    }
}
