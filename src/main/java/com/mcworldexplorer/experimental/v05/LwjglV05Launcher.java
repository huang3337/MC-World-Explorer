package com.mcworldexplorer.experimental.v05;

public final class LwjglV05Launcher {
    private LwjglV05Launcher() {
    }

    public static void main(String[] rawArguments) throws Exception {
        if (rawArguments.length == 1 && "--help".equals(rawArguments[0])) {
            System.out.println(V05Arguments.usage());
            return;
        }
        V05Arguments arguments = V05Arguments.parse(rawArguments);
        long heapUsedBeforePrepare = usedHeap();
        V05ValidationContext context = new V05ValidationPipeline().prepare(arguments);
        long heapUsedAfterPrepare = usedHeap();
        V05RenderMetrics renderMetrics;
        try (LwjglV05Application application = new LwjglV05Application(context)) {
            renderMetrics = application.run();
        }
        Runtime runtime = Runtime.getRuntime();
        V05MemoryMetrics memoryMetrics = new V05MemoryMetrics(
                heapUsedBeforePrepare,
                heapUsedAfterPrepare,
                usedHeap(),
                runtime.totalMemory(),
                runtime.maxMemory());
        V05ValidationReport report = new V05ValidationReport(
                context, renderMetrics, memoryMetrics, true);
        System.out.println("V0.5 Internal Validation blocks="
                + context.sceneResult().snapshot().orElseThrow().blockCount()
                + " faces=" + context.sceneResult().snapshot().orElseThrow().faceCount()
                + " chunks=" + context.sceneResult().targetStatuses().size());
        if (arguments.report().isPresent()) {
            new V05ReportWriter().write(arguments.report().orElseThrow(), report);
            System.out.println("V0.5 report: " + arguments.report().orElseThrow());
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
