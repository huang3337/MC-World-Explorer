package com.mcworldexplorer.viewer;

public interface ViewerTaskListener {
    default void onProgress(long requestId, ViewerProgress progress) {
    }

    default void onSucceeded(long requestId, ViewerTaskResult result) {
    }

    default void onFailed(long requestId, Throwable failure) {
    }

    default void onCancelled(long requestId) {
    }
}
