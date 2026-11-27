package com.mcworldexplorer.preview;

public interface PreviewGenerationMonitor {
    PreviewGenerationMonitor NONE = new PreviewGenerationMonitor() {
    };

    default boolean isCancelled() {
        return false;
    }

    default void onProgress(int completedChunks, int totalChunks) {
    }
}
