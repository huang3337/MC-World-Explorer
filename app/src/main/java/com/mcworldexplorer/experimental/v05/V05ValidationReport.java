package com.mcworldexplorer.experimental.v05;

public record V05ValidationReport(
        V05ValidationContext context,
        V05RenderMetrics renderMetrics,
        V05MemoryMetrics memoryMetrics,
        boolean resourcesReleased) {
}
