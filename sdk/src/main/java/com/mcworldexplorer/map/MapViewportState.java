package com.mcworldexplorer.map;

import java.util.Objects;

public final class MapViewportState {
    private final double defaultCenterX;
    private final double defaultCenterZ;
    private final MapDisplayZoom defaultDisplayZoom;
    private double centerX;
    private double centerZ;
    private MapDisplayZoom displayZoom;
    private double visualBlocksPerPixel;

    public MapViewportState(double defaultCenterX, double defaultCenterZ, MapZoomLevel defaultZoom) {
        if (!Double.isFinite(defaultCenterX) || !Double.isFinite(defaultCenterZ)) {
            throw new IllegalArgumentException("viewport center must be finite");
        }
        this.defaultCenterX = defaultCenterX;
        this.defaultCenterZ = defaultCenterZ;
        this.defaultDisplayZoom = MapDisplayZoom.fromTileZoom(
                Objects.requireNonNull(defaultZoom, "defaultZoom"));
        reset();
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public MapZoomLevel zoom() {
        return displayZoom.tileZoom();
    }

    public MapDisplayZoom displayZoom() {
        return displayZoom;
    }

    public double visualBlocksPerPixel() {
        return visualBlocksPerPixel;
    }

    public void centerOn(double worldX, double worldZ) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ)) {
            throw new IllegalArgumentException("viewport center must be finite");
        }
        centerX = worldX;
        centerZ = worldZ;
    }

    public void panPixels(double deltaX, double deltaY) {
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
            throw new IllegalArgumentException("pan delta must be finite");
        }
        centerX -= deltaX * visualBlocksPerPixel;
        centerZ -= deltaY * visualBlocksPerPixel;
    }

    public double worldXAt(double screenX, double viewportWidth) {
        return centerX + (screenX - viewportWidth / 2.0) * visualBlocksPerPixel;
    }

    public double worldZAt(double screenY, double viewportHeight) {
        return centerZ + (screenY - viewportHeight / 2.0) * visualBlocksPerPixel;
    }

    public double screenXFor(double worldX, double viewportWidth) {
        return viewportWidth / 2.0 + (worldX - centerX) / visualBlocksPerPixel;
    }

    public double screenYFor(double worldZ, double viewportHeight) {
        return viewportHeight / 2.0 + (worldZ - centerZ) / visualBlocksPerPixel;
    }

    public void zoomAt(
            MapZoomLevel nextZoom,
            double pointerX,
            double pointerY,
            double viewportWidth,
            double viewportHeight) {
        zoomAt(
                MapDisplayZoom.fromTileZoom(Objects.requireNonNull(nextZoom, "nextZoom")),
                pointerX,
                pointerY,
                viewportWidth,
                viewportHeight);
    }

    public void zoomAt(
            MapDisplayZoom nextZoom,
            double pointerX,
            double pointerY,
            double viewportWidth,
            double viewportHeight) {
        Objects.requireNonNull(nextZoom, "nextZoom");
        double anchorX = worldXAt(pointerX, viewportWidth);
        double anchorZ = worldZAt(pointerY, viewportHeight);
        displayZoom = nextZoom;
        visualBlocksPerPixel = nextZoom.blocksPerPixel();
        centerX = anchorX - (pointerX - viewportWidth / 2.0) * visualBlocksPerPixel;
        centerZ = anchorZ - (pointerY - viewportHeight / 2.0) * visualBlocksPerPixel;
    }

    public void zoomVisualAt(
            double nextBlocksPerPixel,
            double pointerX,
            double pointerY,
            double viewportWidth,
            double viewportHeight) {
        if (!Double.isFinite(nextBlocksPerPixel)
                || nextBlocksPerPixel < MapDisplayZoom.PIXELS_4.blocksPerPixel()
                || nextBlocksPerPixel > MapDisplayZoom.BLOCKS_16.blocksPerPixel()) {
            throw new IllegalArgumentException(
                    "visual zoom must stay within 0.25..16 blocks per pixel");
        }
        double anchorX = worldXAt(pointerX, viewportWidth);
        double anchorZ = worldZAt(pointerY, viewportHeight);
        visualBlocksPerPixel = nextBlocksPerPixel;
        centerX = anchorX - (pointerX - viewportWidth / 2.0) * visualBlocksPerPixel;
        centerZ = anchorZ - (pointerY - viewportHeight / 2.0) * visualBlocksPerPixel;
    }

    public void commitZoom(MapZoomLevel nextZoom) {
        displayZoom = MapDisplayZoom.fromTileZoom(
                Objects.requireNonNull(nextZoom, "nextZoom"));
    }

    public void commitZoom(MapDisplayZoom nextZoom) {
        displayZoom = Objects.requireNonNull(nextZoom, "nextZoom");
    }

    public void setView(
            double worldX,
            double worldZ,
            MapZoomLevel nextZoom) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ)) {
            throw new IllegalArgumentException("viewport center must be finite");
        }
        displayZoom = MapDisplayZoom.fromTileZoom(
                Objects.requireNonNull(nextZoom, "nextZoom"));
        centerX = worldX;
        centerZ = worldZ;
        visualBlocksPerPixel = displayZoom.blocksPerPixel();
    }

    public void reset() {
        centerX = defaultCenterX;
        centerZ = defaultCenterZ;
        displayZoom = defaultDisplayZoom;
        visualBlocksPerPixel = defaultDisplayZoom.blocksPerPixel();
    }
}
