package com.mcworldexplorer.experimental.v05;

public record V05RenderMetrics(
        long uploadNanos,
        long firstFrameNanos,
        long frameCount,
        double averageFps,
        String openGlVendor,
        String openGlRenderer,
        String openGlVersion) {
}
