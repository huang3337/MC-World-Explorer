package com.mcworldexplorer.preview;

import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;

public final class ChunkSectionView {
    private final ChunkSection section;

    ChunkSectionView(ChunkSection section) {
        this.section = section;
    }

    public int sectionY() {
        return section.sectionY();
    }

    public boolean contains(String blockName) {
        return section.contains(blockName);
    }

    public String blockName(int localX, int localY, int localZ)
            throws SurfaceSamplingException {
        try {
            return section.blockState(localX, localY, localZ).name();
        } catch (ChunkDecodeException e) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.PALETTE_INDEX_OUT_OF_RANGE, e.getMessage(), e);
        }
    }
}
