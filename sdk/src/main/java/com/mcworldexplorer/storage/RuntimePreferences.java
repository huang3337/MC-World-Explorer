package com.mcworldexplorer.storage;

import java.util.List;
import java.util.Properties;

/** Portable map and memory preferences. A zero heap size leaves JVM ergonomics in effect. */
public record RuntimePreferences(MapMode mapMode, int heapMiB, int scanWorkers, String mapAdapter) {
    // Older installations keep their startup behavior until the user picks a physical adapter.
    public RuntimePreferences(MapMode mapMode, int heapMiB, int scanWorkers) {
        this(mapMode, heapMiB, scanWorkers, "");
    }
    public boolean requestsGpu() { return !mapAdapter.isEmpty() || mapMode.requestsGpu(); }
    public static boolean validAdapter(String id) {
        return id != null && id.matches("[0-9A-F]{1,4}&[0-9A-F]{1,4}&[0-9A-F]{1,8}");
    }
    public enum MapMode {
        AUTO("auto", "自动 · 跟随系统"), POWER("power", "省电 · 优先核显"),
        PERFORMANCE("performance", "高性能 · 优先独显"), SOFTWARE("sw", "CPU 软件渲染");

        private final String key;
        private final String label;
        MapMode(String key, String label) { this.key = key; this.label = label; }
        public boolean requestsGpu() { return this == POWER || this == PERFORMANCE; }
        @Override public String toString() { return label; }
    }
    public static final List<Integer> HEAP_OPTIONS = List.of(0, 1024, 2048, 4096);

    public RuntimePreferences(boolean softwareMap, int heapMiB, int scanWorkers) {
        this(softwareMap ? MapMode.SOFTWARE : MapMode.AUTO, heapMiB, scanWorkers);
    }
    public boolean softwareMap() { return mapMode == MapMode.SOFTWARE; }

    public RuntimePreferences {
        if (mapMode == null || mapAdapter == null || (!mapAdapter.isEmpty() && (mapMode != MapMode.AUTO || !validAdapter(mapAdapter)))
                || !HEAP_OPTIONS.contains(heapMiB) || (scanWorkers != 1 && scanWorkers != 2))
            throw new IllegalArgumentException("无效的运行设置");
    }

    static RuntimePreferences read(Properties values) {
        int heap = HEAP_OPTIONS.stream().filter(i -> i.toString().equals(values.getProperty("heap_mib")))
                .findFirst().orElse(0);
        MapMode mode = java.util.Arrays.stream(MapMode.values()).filter(m -> m.key.equals(values.getProperty("map_pipeline")))
                .findFirst().orElse(MapMode.AUTO);
        String adapter = values.getProperty("map_adapter", "");
        if (!validAdapter(adapter)) adapter = "";
        return new RuntimePreferences(adapter.isEmpty() ? mode : MapMode.AUTO, heap,
                "1".equals(values.getProperty("map_scan_workers")) ? 1 : 2, adapter);
    }

    void write(Properties values) {
        if (requestsGpu()) {
            values.remove("map_gpu_reset");
            values.setProperty("map_gpu_scope", "session");
        } else values.remove("map_gpu_scope");
        if (mapAdapter.isEmpty()) {
            values.remove("map_adapter");
            values.setProperty("map_pipeline", mapMode.key);
        } else {
            values.setProperty("map_adapter", mapAdapter);
            values.remove("map_pipeline");
        }
        values.setProperty("heap_mib", Integer.toString(heapMiB));
        values.setProperty("map_scan_workers", Integer.toString(scanWorkers));
    }
}
