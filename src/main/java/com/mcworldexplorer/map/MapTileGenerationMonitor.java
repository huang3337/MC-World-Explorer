package com.mcworldexplorer.map;

public interface MapTileGenerationMonitor {
    MapTileGenerationMonitor NONE = new MapTileGenerationMonitor() {
    };

    default boolean isCancelled() {
        return false;
    }

    default void onProgress(int completedChunks, int totalChunks) {
    }

    default double focusX() {
        return Double.NaN;
    }

    default double focusZ() {
        return Double.NaN;
    }

    default void onPartial(MapTilePartialResult partial) {
    }
}
