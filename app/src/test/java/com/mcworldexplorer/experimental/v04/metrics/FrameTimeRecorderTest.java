package com.mcworldexplorer.experimental.v04.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameTimeRecorderTest {
    @Test
    void summarizesEmptyAndPopulatedSamples() {
        FrameTimeRecorder recorder = new FrameTimeRecorder();
        assertEquals(0, recorder.summary().sampleCount());

        recorder.record(10_000_000);
        recorder.record(20_000_000);
        recorder.record(30_000_000);
        recorder.record(0);
        FrameTimeRecorder.Summary summary = recorder.summary();

        assertEquals(3, summary.sampleCount());
        assertEquals(20_000_000, summary.medianNanos());
        assertEquals(30_000_000, summary.p95Nanos());
        assertEquals(50.0, summary.averageFps(), 0.001);
    }
}
