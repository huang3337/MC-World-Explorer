package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

public final class VoxelSceneResult {
    private final VoxelSceneSnapshot snapshot;
    private final NavigableMap<ChunkCoordinate, ChunkLoadStatus> targetStatuses;
    private final long meshNanos;
    private final boolean cancelled;

    public VoxelSceneResult(
            VoxelSceneSnapshot snapshot,
            Map<ChunkCoordinate, ChunkLoadStatus> targetStatuses,
            long meshNanos,
            boolean cancelled) {
        if (meshNanos < 0) {
            throw new IllegalArgumentException("mesh time must not be negative");
        }
        this.snapshot = snapshot;
        this.targetStatuses = Collections.unmodifiableNavigableMap(
                new TreeMap<>(targetStatuses));
        this.meshNanos = meshNanos;
        this.cancelled = cancelled;
    }

    public Optional<VoxelSceneSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public NavigableMap<ChunkCoordinate, ChunkLoadStatus> targetStatuses() {
        return targetStatuses;
    }

    public long meshNanos() {
        return meshNanos;
    }

    public boolean cancelled() {
        return cancelled;
    }
}
