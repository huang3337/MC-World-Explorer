package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;

public final class BlockNeighborhood {
    private final VoxelAreaLoadResult area;

    public BlockNeighborhood(VoxelAreaLoadResult area) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        this.area = area;
    }

    public Query query(long worldX, int y, long worldZ) throws ChunkDecodeException {
        int chunkX = Math.toIntExact(Math.floorDiv(worldX, 16L));
        int chunkZ = Math.toIntExact(Math.floorDiv(worldZ, 16L));
        ChunkCoordinate coordinate = new ChunkCoordinate(chunkX, chunkZ);
        ChunkLoadResult result = area.result(coordinate);
        if (result.status() == ChunkLoadStatus.SUCCESS) {
            return new Query(
                    result.chunk().blockState(
                            (int) Math.floorMod(worldX, 16L),
                            y,
                            (int) Math.floorMod(worldZ, 16L)),
                    result.status(),
                    coordinate,
                    "");
        }
        return new Query(BlockState.AIR, result.status(), coordinate, result.detail());
    }

    public record Query(
            BlockState state,
            ChunkLoadStatus sourceStatus,
            ChunkCoordinate sourceCoordinate,
            String detail) {
        public boolean unavailableFailure() {
            return sourceStatus == ChunkLoadStatus.UNSUPPORTED
                    || sourceStatus == ChunkLoadStatus.CORRUPT
                    || sourceStatus == ChunkLoadStatus.CANCELLED;
        }
    }
}
