package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VoxelSceneBuilder {
    public VoxelSceneResult build(VoxelAreaLoadResult area, CancellationSignal cancellation) {
        if (area == null || cancellation == null) {
            throw new IllegalArgumentException("area and cancellation must not be null");
        }
        long start = System.nanoTime();
        Map<ChunkCoordinate, ChunkLoadStatus> statuses = new LinkedHashMap<>();
        List<ChunkMeshSnapshot> meshes = new ArrayList<>();
        boolean cancelled = false;
        ChunkMesher mesher = new ChunkMesher();

        for (ChunkCoordinate coordinate : area.target().coordinates()) {
            ChunkLoadResult load = area.result(coordinate);
            if (load.status() != ChunkLoadStatus.SUCCESS) {
                statuses.put(coordinate, load.status());
                continue;
            }
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
                statuses.put(coordinate, ChunkLoadStatus.CANCELLED);
                cancelled = true;
                continue;
            }
            try {
                meshes.add(mesher.build(area, coordinate, cancellation));
                statuses.put(coordinate, ChunkLoadStatus.SUCCESS);
            } catch (java.util.concurrent.CancellationException e) {
                statuses.put(coordinate, ChunkLoadStatus.CANCELLED);
                cancelled = true;
            } catch (ChunkDecodeException e) {
                statuses.put(coordinate, ChunkLoadStatus.CORRUPT);
            }
        }

        VoxelSceneSnapshot snapshot = meshes.isEmpty() ? null : snapshot(area, meshes);
        return new VoxelSceneResult(snapshot, statuses, System.nanoTime() - start, cancelled);
    }

    private static VoxelSceneSnapshot snapshot(
            VoxelAreaLoadResult area,
            List<ChunkMeshSnapshot> meshes) {
        long anchorX = Math.multiplyExact((long) area.target().minX(), 16L);
        long anchorZ = Math.multiplyExact((long) area.target().minZ(), 16L);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        Map<String, MeshWarning> warnings = new LinkedHashMap<>();
        for (ChunkMeshSnapshot mesh : meshes) {
            double offsetX = mesh.worldOriginX() - anchorX;
            double offsetZ = mesh.worldOriginZ() - anchorZ;
            minX = Math.min(minX, mesh.bounds().minX() + offsetX);
            minY = Math.min(minY, mesh.bounds().minY());
            minZ = Math.min(minZ, mesh.bounds().minZ() + offsetZ);
            maxX = Math.max(maxX, mesh.bounds().maxX() + offsetX);
            maxY = Math.max(maxY, mesh.bounds().maxY());
            maxZ = Math.max(maxZ, mesh.bounds().maxZ() + offsetZ);
            for (MeshWarning warning : mesh.warnings()) {
                String warningKey = warning.code() == MeshWarning.Code.FALLBACK_BLOCK
                        ? warning.code() + ":" + warning.detail()
                        : warning.code() + ":" + warning.coordinate() + ":" + warning.detail();
                MeshWarning existing = warnings.get(warningKey);
                if (existing == null) {
                    warnings.put(warningKey, warning);
                } else {
                    warnings.put(warningKey, new MeshWarning(
                            existing.code(),
                            existing.coordinate(),
                            existing.detail(),
                            Math.addExact(existing.occurrences(), warning.occurrences())));
                }
            }
        }
        MeshBounds bounds = new MeshBounds(minX, minY, minZ, maxX, maxY, maxZ);
        return new VoxelSceneSnapshot(
                anchorX,
                anchorZ,
                meshes,
                bounds,
                meshes.stream().mapToInt(ChunkMeshSnapshot::blockCount).sum(),
                meshes.stream().mapToInt(ChunkMeshSnapshot::faceCount).sum(),
                meshes.stream().mapToInt(ChunkMeshSnapshot::vertexCount).sum(),
                meshes.stream().mapToInt(ChunkMeshSnapshot::indexCount).sum(),
                List.copyOf(warnings.values()));
    }
}
