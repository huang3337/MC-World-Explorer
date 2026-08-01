package com.mcworldexplorer.experimental.v04;

import com.mcworldexplorer.experimental.v04.mesh.RenderSnapshot;
import com.mcworldexplorer.experimental.v04.metrics.V04MetricsCollector;
import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;

public record V04TrialContext(
        V04Arguments arguments,
        RenderSnapshot snapshot,
        OrbitCameraState camera,
        V04MetricsCollector metrics) {
}
