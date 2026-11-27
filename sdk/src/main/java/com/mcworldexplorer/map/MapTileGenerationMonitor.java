package com.mcworldexplorer.map;

public interface MapTileGenerationMonitor {
    MapTileGenerationMonitor NONE = new MapTileGenerationMonitor() {
    };

    default boolean isCancelled() {
        return false;
    }

    default boolean isObsolete() { return false; }

    default boolean needsChunk(int x, int z) { return true; }

    default void onProgress(int completedChunks, int totalChunks) {
    }

    default double focusX() {
        return Double.NaN;
    }

    default double focusZ() {
        return Double.NaN;
    }

    /** 可见视口的中心预览；后台导出等消费者保持原有顺序。 */
    default boolean centerFirst() { return false; }

    default void onPartial(MapTilePartialResult partial) {
    }
}
