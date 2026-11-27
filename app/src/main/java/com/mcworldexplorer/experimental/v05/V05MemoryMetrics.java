package com.mcworldexplorer.experimental.v05;

public record V05MemoryMetrics(
        long heapUsedBeforePrepare,
        long heapUsedAfterPrepare,
        long heapUsedAfterRender,
        long heapCommittedAfterRender,
        long maxHeap) {
    public V05MemoryMetrics {
        if (heapUsedBeforePrepare < 0
                || heapUsedAfterPrepare < 0
                || heapUsedAfterRender < 0
                || heapCommittedAfterRender < 0
                || maxHeap < 0) {
            throw new IllegalArgumentException("memory metrics must not be negative");
        }
    }
}
