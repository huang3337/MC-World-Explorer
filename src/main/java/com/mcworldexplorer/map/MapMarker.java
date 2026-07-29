package com.mcworldexplorer.map;

public record MapMarker(
        MapMarkerType type,
        String dimensionId,
        int x,
        int y,
        int z,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ) {

    public MapMarker {
        if (type == null || dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("marker type and dimension must be present");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("marker bounds are invalid");
        }
    }

    public static MapMarker point(
            MapMarkerType type,
            String dimensionId,
            int x,
            int y,
            int z) {
        return new MapMarker(type, dimensionId, x, y, z, x, y, z, x, y, z);
    }

    public static MapMarker portal(
            String dimensionId,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
        return new MapMarker(
                MapMarkerType.NETHER_PORTAL,
                dimensionId,
                midpoint(minX, maxX),
                midpoint(minY, maxY),
                midpoint(minZ, maxZ),
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ);
    }

    private static int midpoint(int min, int max) {
        return (int) (min + ((long) max - min) / 2L);
    }
}
