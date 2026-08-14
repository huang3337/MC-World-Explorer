package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.data.ChunkRectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewerRangePresetTest {
    @Test
    void mapsNegativeWorldCoordinatesUsingFloorDivision() {
        assertEquals(
                ChunkRectangle.target(-2, -2, 0, 0),
                ViewerRangePreset.THREE.atWorld(-0.1, -0.1));
        assertEquals(
                ChunkRectangle.target(-2, -2, 0, 0),
                ViewerRangePreset.THREE.atWorld(-16, -16));
    }

    @Test
    void createsAllConfirmedSquareRanges() {
        assertEquals(1, ViewerRangePreset.ONE.atWorld(0, 0).width());
        assertEquals(3, ViewerRangePreset.THREE.atWorld(0, 0).width());
        assertEquals(5, ViewerRangePreset.FIVE.atWorld(0, 0).width());
        assertEquals(8, ViewerRangePreset.EIGHT.atWorld(0, 0).width());
        assertEquals(64, ViewerRangePreset.EIGHT.chunkCount());
    }

    @Test
    void rejectsUnsupportedOrNonFiniteRanges() {
        assertThrows(IllegalArgumentException.class, () -> ViewerRangePreset.fromChunks(2));
        assertThrows(IllegalArgumentException.class,
                () -> ViewerRangePreset.THREE.atWorld(Double.NaN, 0));
    }
}
