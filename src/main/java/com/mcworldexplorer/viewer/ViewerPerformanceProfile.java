package com.mcworldexplorer.viewer;

import java.util.Locale;
import java.util.Set;

public enum ViewerPerformanceProfile {
    BASIC("基础档", Set.of(ViewerRangePreset.ONE, ViewerRangePreset.THREE), 2, 256L * 1024 * 1024),
    ENHANCED("增强档", Set.of(ViewerRangePreset.values()), 4, 512L * 1024 * 1024);

    private final String displayName;
    private final Set<ViewerRangePreset> allowedRanges;
    private final int maxCachedScenes;
    private final long maxCachedBytes;

    ViewerPerformanceProfile(
            String displayName,
            Set<ViewerRangePreset> allowedRanges,
            int maxCachedScenes,
            long maxCachedBytes) {
        this.displayName = displayName;
        this.allowedRanges = Set.copyOf(allowedRanges);
        this.maxCachedScenes = maxCachedScenes;
        this.maxCachedBytes = maxCachedBytes;
    }

    public String displayName() {
        return displayName;
    }

    public boolean allows(ViewerRangePreset preset) {
        return allowedRanges.contains(preset);
    }

    public Set<ViewerRangePreset> allowedRanges() {
        return allowedRanges;
    }

    public ViewerRangePreset constrain(ViewerRangePreset preset) {
        return allows(preset) ? preset : ViewerRangePreset.THREE;
    }

    public int maxCachedScenes() {
        return maxCachedScenes;
    }

    public long maxCachedBytes() {
        return maxCachedBytes;
    }

    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ViewerPerformanceProfile fromSetting(String value) {
        if (value == null || value.isBlank()) {
            return BASIC;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BASIC;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
