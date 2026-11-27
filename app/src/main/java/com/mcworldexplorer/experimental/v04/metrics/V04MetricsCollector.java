package com.mcworldexplorer.experimental.v04.metrics;

import com.mcworldexplorer.experimental.v04.V04Arguments;
import com.mcworldexplorer.experimental.v04.data.VoxelChunkLoadResult;
import com.mcworldexplorer.experimental.v04.mesh.RenderSnapshot;

import java.lang.management.ManagementFactory;

public final class V04MetricsCollector {
    private final String backend;
    private final V04Arguments arguments;
    private final VoxelChunkLoadResult load;
    private final RenderSnapshot snapshot;
    private final long meshNanos;
    private final long processStartNanos;
    private final long heapBeforeBytes;
    private final FrameTimeRecorder frameTimes = new FrameTimeRecorder();
    private long backendUploadNanos;
    private long firstFrameNanos;

    public V04MetricsCollector(
            String backend,
            V04Arguments arguments,
            VoxelChunkLoadResult load,
            RenderSnapshot snapshot,
            long meshNanos,
            long processStartNanos,
            long heapBeforeBytes) {
        this.backend = backend;
        this.arguments = arguments;
        this.load = load;
        this.snapshot = snapshot;
        this.meshNanos = meshNanos;
        this.processStartNanos = processStartNanos;
        this.heapBeforeBytes = heapBeforeBytes;
    }

    public void recordBackendUpload(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("upload duration must not be negative");
        }
        backendUploadNanos = nanos;
    }

    public void markFirstFrame(long nowNanos) {
        if (firstFrameNanos == 0) {
            firstFrameNanos = Math.max(0, nowNanos - processStartNanos);
        }
    }

    public FrameTimeRecorder frameTimes() {
        return frameTimes;
    }

    public V04RunMetrics snapshot() {
        return new V04RunMetrics(
                backend,
                arguments.world(),
                arguments.dimensionId(),
                arguments.chunkX(),
                arguments.chunkZ(),
                load.regionReadNanos(),
                load.parseNanos(),
                meshNanos,
                backendUploadNanos,
                firstFrameNanos,
                snapshot.batches().size(),
                snapshot.blockCount(),
                snapshot.solidBlockCount(),
                snapshot.fluidBlockCount(),
                snapshot.faceCount(),
                snapshot.fluidFaceCount(),
                snapshot.vertexCount(),
                snapshot.indexCount(),
                snapshot.bounds().minX(),
                snapshot.bounds().minY(),
                snapshot.bounds().minZ(),
                snapshot.bounds().maxX(),
                snapshot.bounds().maxY(),
                snapshot.bounds().maxZ(),
                heapBeforeBytes,
                heapUsed(),
                frameTimes.summary(),
                snapshot.boundaryComplete(),
                snapshot.warnings().stream()
                        .map(warning -> warning.direction() + " "
                                + warning.chunkX() + "," + warning.chunkZ()
                                + ": " + warning.message())
                        .toList());
    }

    public static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
