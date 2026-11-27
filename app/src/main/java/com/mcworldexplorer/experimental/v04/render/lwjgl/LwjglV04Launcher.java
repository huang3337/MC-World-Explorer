package com.mcworldexplorer.experimental.v04.render.lwjgl;

import com.mcworldexplorer.experimental.v04.V04Arguments;
import com.mcworldexplorer.experimental.v04.V04TrialContext;
import com.mcworldexplorer.experimental.v04.V04TrialPipeline;
import com.mcworldexplorer.experimental.v04.metrics.V04ReportWriter;
import com.mcworldexplorer.experimental.v04.metrics.V04RunMetrics;

public final class LwjglV04Launcher {
    private LwjglV04Launcher() {
    }

    public static void main(String[] rawArguments) throws Exception {
        long processStartNanos = System.nanoTime();
        V04Arguments arguments = V04Arguments.parse(rawArguments);
        V04TrialContext context = new V04TrialPipeline().prepare(
                arguments, "lwjgl", processStartNanos);
        try (LwjglV04Renderer renderer = new LwjglV04Renderer(context)) {
            renderer.run();
        }
        V04RunMetrics metrics = context.metrics().snapshot();
        System.out.println("V0.4 backend=" + metrics.backend()
                + " blocks=" + metrics.blockCount()
                + " faces=" + metrics.faceCount()
                + " vertices=" + metrics.vertexCount()
                + " firstFrameNanos=" + metrics.firstFrameNanos()
                + " averageFps=" + metrics.frameTimes().averageFps());
        if (arguments.report().isPresent()) {
            new V04ReportWriter().write(arguments.report().orElseThrow(), metrics);
            System.out.println("V0.4 report: " + arguments.report().orElseThrow());
        }
    }
}
