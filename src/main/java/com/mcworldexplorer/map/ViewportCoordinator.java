package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ViewportCoordinator {
    public List<MapTileKey> visibleKeys(
            String worldId,
            String dimensionId,
            PreviewLayer layer,
            MapViewportState state,
            double viewportWidth,
            double viewportHeight,
            String renderVersion) {
        if (state == null || viewportWidth <= 0 || viewportHeight <= 0
                || !Double.isFinite(viewportWidth) || !Double.isFinite(viewportHeight)) {
            return List.of();
        }

        return keys(
                worldId,
                dimensionId,
                layer,
                state,
                viewportWidth,
                viewportHeight,
                renderVersion,
                0);
    }

    public List<MapTileKey> prefetchKeys(
            String worldId,
            String dimensionId,
            PreviewLayer layer,
            MapViewportState state,
            double viewportWidth,
            double viewportHeight,
            String renderVersion) {
        List<MapTileKey> visible = visibleKeys(
                worldId,
                dimensionId,
                layer,
                state,
                viewportWidth,
                viewportHeight,
                renderVersion);
        java.util.Set<MapTileKey> visibleSet = java.util.Set.copyOf(visible);
        return keys(
                worldId,
                dimensionId,
                layer,
                state,
                viewportWidth,
                viewportHeight,
                renderVersion,
                1).stream().filter(key -> !visibleSet.contains(key)).toList();
    }

    private static List<MapTileKey> keys(
            String worldId,
            String dimensionId,
            PreviewLayer layer,
            MapViewportState state,
            double viewportWidth,
            double viewportHeight,
            String renderVersion,
            int margin) {
        if (state == null || viewportWidth <= 0 || viewportHeight <= 0
                || !Double.isFinite(viewportWidth) || !Double.isFinite(viewportHeight)) {
            return List.of();
        }
        double lastScreenX = Math.max(0, viewportWidth - 0.000001);
        double lastScreenY = Math.max(0, viewportHeight - 0.000001);
        long minWorldX = floor(state.worldXAt(0, viewportWidth));
        long maxWorldX = floor(state.worldXAt(lastScreenX, viewportWidth));
        long minWorldZ = floor(state.worldZAt(0, viewportHeight));
        long maxWorldZ = floor(state.worldZAt(lastScreenY, viewportHeight));
        long minTileX = MapTileKey.tileCoordinate(minWorldX, state.zoom()) - margin;
        long maxTileX = MapTileKey.tileCoordinate(maxWorldX, state.zoom()) + margin;
        long minTileZ = MapTileKey.tileCoordinate(minWorldZ, state.zoom()) - margin;
        long maxTileZ = MapTileKey.tileCoordinate(maxWorldZ, state.zoom()) + margin;
        long centerTileX = MapTileKey.tileCoordinate(floor(state.centerX()), state.zoom());
        long centerTileZ = MapTileKey.tileCoordinate(floor(state.centerZ()), state.zoom());

        List<MapTileKey> keys = new ArrayList<>();
        for (long tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (long tileX = minTileX; tileX <= maxTileX; tileX++) {
                keys.add(new MapTileKey(
                        worldId,
                        dimensionId,
                        layer,
                        state.zoom(),
                        tileX,
                        tileZ,
                        renderVersion));
            }
        }
        keys.sort(Comparator
                .comparingLong((MapTileKey key) -> distanceSquared(key, centerTileX, centerTileZ))
                .thenComparingLong(MapTileKey::tileZ)
                .thenComparingLong(MapTileKey::tileX));
        return List.copyOf(keys);
    }

    private static long floor(double value) {
        if (value <= Long.MIN_VALUE || value >= Long.MAX_VALUE) {
            throw new IllegalArgumentException("viewport coordinate is outside supported range");
        }
        return (long) Math.floor(value);
    }

    private static long distanceSquared(MapTileKey key, long centerTileX, long centerTileZ) {
        long dx = key.tileX() - centerTileX;
        long dz = key.tileZ() - centerTileZ;
        try {
            return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
