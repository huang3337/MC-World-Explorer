package com.mcworldexplorer.preview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewLayerTest {
    @Test
    void splitsDimensionRangeIntoThirtyTwoBlockBands() {
        DimensionHeightRange range = new DimensionHeightRange(-64, 319);

        List<PreviewLayer> layers = range.layers();

        assertEquals(13, layers.size());
        assertEquals(PreviewLayer.surfaceOverview(), layers.getFirst());
        assertEquals(PreviewLayer.heightBand(-64, -33), layers.get(1));
        assertEquals(PreviewLayer.heightBand(288, 319), layers.getLast());
        assertEquals(PreviewLayer.heightBand(64, 95), range.bandContaining(70));
    }

    @Test
    void keepsPartialFinalBandInsideActualDimensionRange() {
        DimensionHeightRange range = new DimensionHeightRange(-32, 80);

        assertEquals(PreviewLayer.heightBand(64, 80), range.layers().getLast());
        assertEquals(PreviewLayer.heightBand(64, 80), range.bandContaining(200));
    }

    @Test
    void rejectsInvalidRangesAndOversizedBands() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionHeightRange(1, 0));
        assertThrows(IllegalArgumentException.class, () -> PreviewLayer.heightBand(0, 32));
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewLayer(PreviewLayerType.SURFACE_OVERVIEW, 1, 1));
    }
}
