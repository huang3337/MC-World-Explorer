package com.mcworldexplorer.viewer;

import java.nio.file.Path;
import java.util.Objects;

public record RegionFileStamp(
        Path path,
        boolean exists,
        long size,
        long modifiedMillis) implements Comparable<RegionFileStamp> {
    public RegionFileStamp {
        Objects.requireNonNull(path, "path");
        path = path.toAbsolutePath().normalize();
        if (exists && (size < 0 || modifiedMillis < 0)) {
            throw new IllegalArgumentException("existing Region stamp must have non-negative values");
        }
        if (!exists && (size != -1 || modifiedMillis != -1)) {
            throw new IllegalArgumentException("missing Region stamp must use -1 values");
        }
    }

    @Override
    public int compareTo(RegionFileStamp other) {
        return path.toString().compareToIgnoreCase(other.path.toString());
    }
}
