package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class VoxelAreaLoadResult {
    private final Path world;
    private final WorldDimension dimension;
    private final ChunkRectangle target;
    private final NavigableMap<ChunkCoordinate, ChunkLoadResult> results;

    public VoxelAreaLoadResult(
            Path world,
            WorldDimension dimension,
            ChunkRectangle target,
            Map<ChunkCoordinate, ChunkLoadResult> results) {
        this.world = Objects.requireNonNull(world, "world");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.target = Objects.requireNonNull(target, "target");
        TreeMap<ChunkCoordinate, ChunkLoadResult> ordered = new TreeMap<>(results);
        for (ChunkCoordinate coordinate : target.expanded(1).coordinates()) {
            if (!ordered.containsKey(coordinate)) {
                throw new IllegalArgumentException("missing load result for " + coordinate);
            }
        }
        this.results = Collections.unmodifiableNavigableMap(ordered);
    }

    public Path world() {
        return world;
    }

    public WorldDimension dimension() {
        return dimension;
    }

    public ChunkRectangle target() {
        return target;
    }

    public NavigableMap<ChunkCoordinate, ChunkLoadResult> results() {
        return results;
    }

    public ChunkLoadResult result(ChunkCoordinate coordinate) {
        ChunkLoadResult result = results.get(coordinate);
        if (result == null) {
            throw new IllegalArgumentException("coordinate is outside the loaded area: " + coordinate);
        }
        return result;
    }
}
