package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;
import com.mcworldexplorer.preview.BlockColorPalette;

public final class VoxelColorResolver {
    public static final int UNKNOWN_RGB = 0x808080;

    private VoxelColorResolver() {
    }

    public static int resolve(VoxelBlockState state) {
        BlockColorPalette.BlockColor color = BlockColorPalette.resolve(state.name());
        return color.known() ? color.rgb() : UNKNOWN_RGB;
    }
}
