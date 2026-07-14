package com.mcworldexplorer.preview;

import java.util.Objects;

public record SurfaceColumn(String blockName, int y) {
    public SurfaceColumn {
        Objects.requireNonNull(blockName, "blockName");
    }
}
