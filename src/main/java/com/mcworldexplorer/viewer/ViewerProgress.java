package com.mcworldexplorer.viewer;

import java.util.Objects;

public record ViewerProgress(
        ViewerProgressStage stage,
        int completed,
        int total,
        String message) {
    public ViewerProgress {
        Objects.requireNonNull(stage, "stage");
        if (completed < 0 || total < 0 || completed > total) {
            throw new IllegalArgumentException("viewer progress counts are invalid");
        }
        message = message == null ? "" : message;
    }

    public boolean determinate() {
        return total > 0;
    }

    public double fraction() {
        return determinate() ? (double) completed / total : -1;
    }
}
