package com.mcworldexplorer.preview;

import java.util.Objects;

public record PreviewCenter(int x, int z, PreviewCenterSource source) {
    public PreviewCenter {
        Objects.requireNonNull(source, "source");
    }
}
