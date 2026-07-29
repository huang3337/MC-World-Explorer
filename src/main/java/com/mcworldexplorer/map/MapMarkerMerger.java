package com.mcworldexplorer.map;

import java.util.ArrayList;
import java.util.List;

public final class MapMarkerMerger {
    private MapMarkerMerger() {
    }

    public static List<MapMarker> mergePortals(List<MapMarker> markers) {
        if (markers == null) {
            throw new IllegalArgumentException("markers must not be null");
        }
        List<MapMarker> merged = new ArrayList<>();
        for (MapMarker marker : markers) {
            if (marker.type() != MapMarkerType.NETHER_PORTAL) {
                merged.add(marker);
                continue;
            }
            MapMarker current = marker;
            boolean changed;
            do {
                changed = false;
                for (int i = 0; i < merged.size(); i++) {
                    MapMarker existing = merged.get(i);
                    if (canMerge(existing, current)) {
                        current = merge(existing, current);
                        merged.remove(i);
                        changed = true;
                        break;
                    }
                }
            } while (changed);
            merged.add(current);
        }
        return List.copyOf(merged);
    }

    private static boolean canMerge(MapMarker first, MapMarker second) {
        return first.type() == MapMarkerType.NETHER_PORTAL
                && second.type() == MapMarkerType.NETHER_PORTAL
                && first.dimensionId().equals(second.dimensionId())
                && overlapsOrTouches(first.minX(), first.maxX(), second.minX(), second.maxX())
                && overlapsOrTouches(first.minY(), first.maxY(), second.minY(), second.maxY())
                && overlapsOrTouches(first.minZ(), first.maxZ(), second.minZ(), second.maxZ());
    }

    private static boolean overlapsOrTouches(int firstMin, int firstMax, int secondMin, int secondMax) {
        return (long) firstMin <= (long) secondMax + 1
                && (long) secondMin <= (long) firstMax + 1;
    }

    private static MapMarker merge(MapMarker first, MapMarker second) {
        return MapMarker.portal(
                first.dimensionId(),
                Math.min(first.minX(), second.minX()),
                Math.min(first.minY(), second.minY()),
                Math.min(first.minZ(), second.minZ()),
                Math.max(first.maxX(), second.maxX()),
                Math.max(first.maxY(), second.maxY()),
                Math.max(first.maxZ(), second.maxZ()));
    }
}
