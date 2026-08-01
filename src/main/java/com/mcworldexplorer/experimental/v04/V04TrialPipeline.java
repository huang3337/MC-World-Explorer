package com.mcworldexplorer.experimental.v04;

import com.mcworldexplorer.experimental.v04.data.VoxelChunkLoadResult;
import com.mcworldexplorer.experimental.v04.data.VoxelChunkLoader;
import com.mcworldexplorer.experimental.v04.data.VoxelLoadException;
import com.mcworldexplorer.experimental.v04.mesh.RenderSnapshot;
import com.mcworldexplorer.experimental.v04.mesh.VoxelMesher;
import com.mcworldexplorer.experimental.v04.metrics.V04MetricsCollector;
import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;

import java.io.IOException;

public final class V04TrialPipeline {
    public V04TrialContext prepare(
            V04Arguments arguments,
            String backend,
            long processStartNanos) throws IOException, VoxelLoadException {
        long heapBefore = V04MetricsCollector.heapUsed();
        VoxelChunkLoadResult load = new VoxelChunkLoader().loadMeasured(arguments);
        long meshStart = System.nanoTime();
        RenderSnapshot snapshot;
        try {
            snapshot = new VoxelMesher().build(load.neighborhood());
        } catch (com.mcworldexplorer.experimental.v04.data.VoxelDataException e) {
            throw new VoxelLoadException(
                    VoxelLoadException.Reason.TARGET_CHUNK_UNREADABLE,
                    arguments.chunkX(),
                    arguments.chunkZ(),
                    "failed to query target chunk while building mesh: " + e.getMessage(),
                    e);
        }
        long meshNanos = System.nanoTime() - meshStart;
        V04MetricsCollector metrics = new V04MetricsCollector(
                backend,
                arguments,
                load,
                snapshot,
                meshNanos,
                processStartNanos,
                heapBefore);
        return new V04TrialContext(
                arguments,
                snapshot,
                OrbitCameraState.forBounds(snapshot.bounds()),
                metrics);
    }
}
