package com.mcworldexplorer.experimental.v04.data;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class VoxelChunkNeighborhood {
    private final VoxelChunk target;
    private final Map<Direction, Optional<VoxelChunk>> neighbors;
    private final List<VoxelLoadWarning> warnings;

    public VoxelChunkNeighborhood(
            VoxelChunk target,
            Map<Direction, Optional<VoxelChunk>> neighbors,
            List<VoxelLoadWarning> warnings) {
        this.target = target;
        EnumMap<Direction, Optional<VoxelChunk>> copy = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            copy.put(direction, neighbors.getOrDefault(direction, Optional.empty()));
        }
        this.neighbors = Map.copyOf(copy);
        this.warnings = List.copyOf(warnings);
    }

    public VoxelChunk target() {
        return target;
    }

    public List<VoxelLoadWarning> warnings() {
        return warnings;
    }

    public boolean boundaryComplete() {
        return warnings.isEmpty();
    }

    public VoxelBlockState blockState(int worldX, int y, int worldZ)
            throws VoxelDataException {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        VoxelChunk chunk;
        if (chunkX == target.chunkX() && chunkZ == target.chunkZ()) {
            chunk = target;
        } else {
            Direction direction = Direction.fromOffset(
                    chunkX - target.chunkX(),
                    chunkZ - target.chunkZ());
            chunk = neighbors.get(direction).orElse(null);
        }
        if (chunk == null) {
            return VoxelBlockState.AIR;
        }
        return chunk.blockState(Math.floorMod(worldX, 16), y, Math.floorMod(worldZ, 16));
    }

    public enum Direction {
        WEST(-1, 0),
        EAST(1, 0),
        NORTH(0, -1),
        SOUTH(0, 1);

        private final int deltaX;
        private final int deltaZ;

        Direction(int deltaX, int deltaZ) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
        }

        public int chunkX(int targetX) {
            return Math.addExact(targetX, deltaX);
        }

        public int chunkZ(int targetZ) {
            return Math.addExact(targetZ, deltaZ);
        }

        static Direction fromOffset(int deltaX, int deltaZ) {
            for (Direction direction : values()) {
                if (direction.deltaX == deltaX && direction.deltaZ == deltaZ) {
                    return direction;
                }
            }
            throw new IllegalArgumentException(
                    "world coordinate is outside the target and four horizontal neighbors");
        }
    }
}
