package com.mcworldexplorer.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerPerformanceProfileTest {
    @Test
    void basicProfileKeepsOnlyConfirmedConservativeRanges() {
        assertTrue(ViewerPerformanceProfile.BASIC.allows(ViewerRangePreset.ONE));
        assertTrue(ViewerPerformanceProfile.BASIC.allows(ViewerRangePreset.THREE));
        assertFalse(ViewerPerformanceProfile.BASIC.allows(ViewerRangePreset.FIVE));
        assertEquals(ViewerRangePreset.THREE,
                ViewerPerformanceProfile.BASIC.constrain(ViewerRangePreset.EIGHT));
        assertEquals(2, ViewerPerformanceProfile.BASIC.maxCachedScenes());
        assertEquals(256L * 1024 * 1024, ViewerPerformanceProfile.BASIC.maxCachedBytes());
    }

    @Test
    void enhancedProfileAllowsMaximumRange() {
        assertTrue(ViewerPerformanceProfile.ENHANCED.allows(ViewerRangePreset.EIGHT));
        assertEquals(4, ViewerPerformanceProfile.ENHANCED.maxCachedScenes());
        assertEquals(512L * 1024 * 1024, ViewerPerformanceProfile.ENHANCED.maxCachedBytes());
    }

    @Test
    void missingOrUnknownSettingFallsBackToBasic() {
        assertEquals(ViewerPerformanceProfile.BASIC,
                ViewerPerformanceProfile.fromSetting(null));
        assertEquals(ViewerPerformanceProfile.BASIC,
                ViewerPerformanceProfile.fromSetting("future"));
        assertEquals(ViewerPerformanceProfile.ENHANCED,
                ViewerPerformanceProfile.fromSetting(" enhanced "));
    }
}
