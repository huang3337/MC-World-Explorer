package com.mcworldexplorer.viewer;

/** UI selection methods; fixed presets retain their existing meaning. */
public enum ViewerRangeOption {
    ONE(ViewerRangePreset.ONE), THREE(ViewerRangePreset.THREE),
    FIVE(ViewerRangePreset.FIVE), EIGHT(ViewerRangePreset.EIGHT), FREE(null);

    private final ViewerRangePreset preset;

    ViewerRangeOption(ViewerRangePreset preset) {
        this.preset = preset;
    }

    public boolean isFree() {
        return this == FREE;
    }

    public ViewerRangePreset preset() {
        if (isFree()) {
            throw new IllegalStateException("free selection has no fixed preset");
        }
        return preset;
    }

    public boolean allowedBy(ViewerPerformanceProfile profile) {
        return isFree() || profile.allows(preset);
    }

    @Override
    public String toString() {
        return isFree() ? "自由框选" : preset.toString();
    }
}
