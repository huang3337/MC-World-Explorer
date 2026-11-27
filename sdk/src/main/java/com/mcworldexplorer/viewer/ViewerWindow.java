package com.mcworldexplorer.viewer;

public interface ViewerWindow extends AutoCloseable {
    void show(ViewerWindowContent content, Completion completion);

    /** Release the displayed scene without closing the reusable window owner. */
    default void clear() { }

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
