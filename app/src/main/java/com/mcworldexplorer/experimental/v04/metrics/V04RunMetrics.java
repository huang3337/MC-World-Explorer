package com.mcworldexplorer.experimental.v04.metrics;

import java.nio.file.Path;
import java.util.List;

public record V04RunMetrics(
        String backend,
        Path world,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long regionReadNanos,
        long parseNanos,
        long meshNanos,
        long backendUploadNanos,
        long firstFrameNanos,
        int batchCount,
        int blockCount,
        int solidBlockCount,
        int fluidBlockCount,
        int faceCount,
        int fluidFaceCount,
        int vertexCount,
        int indexCount,
        double meshMinX,
        double meshMinY,
        double meshMinZ,
        double meshMaxX,
        double meshMaxY,
        double meshMaxZ,
        long heapBeforeBytes,
        long heapAfterBytes,
        FrameTimeRecorder.Summary frameTimes,
        boolean boundaryComplete,
        List<String> warnings) {
    public V04RunMetrics {
        warnings = List.copyOf(warnings);
    }
}
