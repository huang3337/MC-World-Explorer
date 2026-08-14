package com.mcworldexplorer.voxel.io;

@FunctionalInterface
public interface CancellationSignal {
    CancellationSignal NONE = () -> false;

    boolean isCancelled();
}
