package com.mcworldexplorer.viewer;

public interface ViewerWindow extends AutoCloseable {
    void show(ViewerWindowContent content, Completion completion);

    @Override
    void close();

    interface Completion {
        default boolean isCurrent() {
            return true;
        }

        void onShown();

        void onFailed(Throwable failure);
    }
}
