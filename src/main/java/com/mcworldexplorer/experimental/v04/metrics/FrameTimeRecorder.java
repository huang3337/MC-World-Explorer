package com.mcworldexplorer.experimental.v04.metrics;

import java.util.Arrays;

public final class FrameTimeRecorder {
    private static final int MAX_SAMPLES = 120_000;
    private final long[] samples = new long[MAX_SAMPLES];
    private int sampleCount;

    public synchronized void record(long frameNanos) {
        if (frameNanos <= 0) {
            return;
        }
        if (sampleCount < MAX_SAMPLES) {
            samples[sampleCount++] = frameNanos;
        }
    }

    public synchronized Summary summary() {
        if (sampleCount == 0) {
            return new Summary(0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(sorted);
        double total = 0;
        for (long sample : sorted) {
            total += sample;
        }
        double averageNanos = total / sorted.length;
        return new Summary(
                sorted.length,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                1_000_000_000.0 / averageNanos);
    }

    private static long percentile(long[] sorted, double fraction) {
        int index = (int) Math.ceil(sorted.length * fraction) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    public record Summary(
            int sampleCount,
            long medianNanos,
            long p95Nanos,
            double averageFps) {
    }
}
