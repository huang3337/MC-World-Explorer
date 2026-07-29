package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerMerger;
import com.mcworldexplorer.map.MapMarkerType;
import com.mcworldexplorer.map.MapTileBounds;
import com.mcworldexplorer.map.MapTileKey;
import com.mcworldexplorer.map.MapViewportState;
import com.mcworldexplorer.map.MapZoomLevel;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MapViewport extends Region {
    private static final double MARKER_RADIUS = 7;
    private static final double MIN_VISUAL_ZOOM = 1;
    private static final double MAX_VISUAL_ZOOM = 16;
    private static final double WHEEL_ZOOM_DIVISOR = 240;
    private static final int ZOOM_ANIMATION_MILLIS = 120;

    private final Canvas canvas = new Canvas();
    private final Map<MapTileKey, Image> tiles = new HashMap<>();
    private final Map<MapTileKey, List<MapMarker>> tileMarkers = new HashMap<>();
    private final Map<MapTileKey, Image> partialTiles = new HashMap<>();
    private final Map<MapTileKey, List<MapMarker>> partialTileMarkers = new HashMap<>();
    private final List<MapMarker> fixedMarkers = new ArrayList<>();
    private final Tooltip markerTooltip = new Tooltip();
    private final BooleanProperty showPlayer = new SimpleBooleanProperty(true);
    private final BooleanProperty showSpawn = new SimpleBooleanProperty(true);
    private final BooleanProperty showPortals = new SimpleBooleanProperty(true);
    private final PauseTransition zoomSettle = new PauseTransition(Duration.millis(150));
    private final Timeline zoomAnimation = new Timeline();
    private Set<MapTileKey> targetKeys = Set.of();
    private MapViewportState state;
    private Runnable viewportChanged = () -> {
    };
    private Runnable visualChanged = () -> {
    };
    private Runnable zoomTargetChanged = () -> {
    };
    private double dragX;
    private double dragY;
    private double zoomAnchorX;
    private double zoomAnchorY;

    public MapViewport() {
        getChildren().add(canvas);
        setMinSize(0, 0);
        showPlayer.addListener((observable, oldValue, newValue) -> draw());
        showSpawn.addListener((observable, oldValue, newValue) -> draw());
        showPortals.addListener((observable, oldValue, newValue) -> draw());
        zoomSettle.setOnFinished(event -> settleVisualZoom());
        canvas.setOnMousePressed(event -> {
            hideMarkerTooltip();
            if (event.getButton() == MouseButton.PRIMARY) {
                MapMarker marker = markerAt(event.getX(), event.getY());
                if (marker != null) {
                    state.centerOn(marker.x(), marker.z());
                    draw();
                    viewportChanged.run();
                    return;
                }
                dragX = event.getX();
                dragY = event.getY();
                canvas.setCursor(Cursor.CLOSED_HAND);
            }
        });
        canvas.setOnMouseDragged(event -> {
            hideMarkerTooltip();
            if (state == null || !event.isPrimaryButtonDown()) {
                return;
            }
            state.panPixels(event.getX() - dragX, event.getY() - dragY);
            dragX = event.getX();
            dragY = event.getY();
            draw();
            viewportChanged.run();
        });
        canvas.setOnMouseReleased(event -> canvas.setCursor(Cursor.DEFAULT));
        canvas.setOnScroll(event -> {
            hideMarkerTooltip();
            if (state == null || event.getDeltaY() == 0) {
                return;
            }
            zoomAnimation.stop();
            zoomAnchorX = event.getX();
            zoomAnchorY = event.getY();
            double factor = Math.pow(2, -event.getDeltaY() / WHEEL_ZOOM_DIVISOR);
            double next = clampVisualZoom(state.visualBlocksPerPixel() * factor);
            state.zoomVisualAt(
                    next,
                    zoomAnchorX,
                    zoomAnchorY,
                    getWidth(),
                    getHeight());
            draw();
            visualChanged.run();
            zoomSettle.playFromStart();
            event.consume();
        });
        canvas.setOnMouseMoved(event -> {
            MapMarker marker = markerAt(event.getX(), event.getY());
            showMarkerTooltip(marker, event.getScreenX(), event.getScreenY());
            canvas.setCursor(marker == null ? Cursor.DEFAULT : Cursor.HAND);
        });
        canvas.setOnMouseExited(event -> {
            hideMarkerTooltip();
            canvas.setCursor(Cursor.DEFAULT);
        });
    }

    public void setViewportState(MapViewportState state) {
        hideMarkerTooltip();
        zoomSettle.stop();
        zoomAnimation.stop();
        zoomAnimation.getKeyFrames().clear();
        this.state = state;
        clearTiles();
        draw();
    }

    public MapViewportState viewportState() {
        return state;
    }

    public void setOnViewportChanged(Runnable viewportChanged) {
        this.viewportChanged = viewportChanged == null ? () -> {
        } : viewportChanged;
    }

    public void setOnVisualChanged(Runnable visualChanged) {
        this.visualChanged = visualChanged == null ? () -> {
        } : visualChanged;
    }

    public void setOnZoomTargetChanged(Runnable zoomTargetChanged) {
        this.zoomTargetChanged = zoomTargetChanged == null ? () -> {
        } : zoomTargetChanged;
    }

    public void showTile(MapTileKey key, Image image, List<MapMarker> markers) {
        tiles.put(key, image);
        tileMarkers.put(key, List.copyOf(markers));
        partialTiles.remove(key);
        partialTileMarkers.remove(key);
        releaseFallbackIfCovered();
        draw();
    }

    public void showPartialTile(MapTileKey key, Image image, List<MapMarker> markers) {
        if (tiles.containsKey(key) || !targetKeys.contains(key)) {
            return;
        }
        partialTiles.put(key, image);
        partialTileMarkers.put(key, List.copyOf(markers));
        draw();
    }

    public void setTargetTiles(Iterable<MapTileKey> keys) {
        Map<MapTileKey, Boolean> requested = new HashMap<>();
        keys.forEach(key -> requested.put(key, true));
        targetKeys = Set.copyOf(requested.keySet());
        if (targetKeys.isEmpty()) {
            return;
        }
        MapZoomLevel targetZoom = targetKeys.iterator().next().zoom();
        tiles.keySet().removeIf(key ->
                key.zoom() == targetZoom && !targetKeys.contains(key));
        tileMarkers.keySet().removeIf(key ->
                key.zoom() == targetZoom && !targetKeys.contains(key));
        partialTiles.keySet().removeIf(key -> !targetKeys.contains(key));
        partialTileMarkers.keySet().removeIf(key -> !targetKeys.contains(key));
        releaseFallbackIfCovered();
        draw();
    }

    public void clearTiles() {
        tiles.clear();
        tileMarkers.clear();
        partialTiles.clear();
        partialTileMarkers.clear();
        targetKeys = Set.of();
        draw();
    }

    public void setFixedMarkers(List<MapMarker> markers) {
        hideMarkerTooltip();
        fixedMarkers.clear();
        fixedMarkers.addAll(markers);
        draw();
    }

    public BooleanProperty showPlayerProperty() {
        return showPlayer;
    }

    public BooleanProperty showSpawnProperty() {
        return showSpawn;
    }

    public BooleanProperty showPortalsProperty() {
        return showPortals;
    }

    public int loadedTileCount() {
        return tiles.size();
    }

    public void zoomIn() {
        if (state != null) {
            animateToZoom(state.zoom().zoomIn(), getWidth() / 2, getHeight() / 2);
        }
    }

    public void zoomOut() {
        if (state != null) {
            animateToZoom(state.zoom().zoomOut(), getWidth() / 2, getHeight() / 2);
        }
    }

    public void resetView() {
        if (state != null) {
            hideMarkerTooltip();
            zoomSettle.stop();
            zoomAnimation.stop();
            state.reset();
            draw();
            viewportChanged.run();
            zoomTargetChanged.run();
        }
    }

    public void centerOn(double x, double z) {
        if (state != null) {
            hideMarkerTooltip();
            state.centerOn(x, z);
            draw();
            viewportChanged.run();
        }
    }

    public BufferedImage snapshotImage() {
        WritableImage snapshot = canvas.snapshot(null, null);
        BufferedImage output = new BufferedImage(
                (int) snapshot.getWidth(),
                (int) snapshot.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            }
        }
        return output;
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(Math.max(1, getWidth()));
        canvas.setHeight(Math.max(1, getHeight()));
        draw();
    }

    public boolean isShowingTemporaryScale() {
        return state != null && (Math.abs(
                state.visualBlocksPerPixel() - state.zoom().blocksPerPixel()) > 0.000001
                || tiles.keySet().stream().anyMatch(key -> key.zoom() != state.zoom()));
    }

    public boolean hasCompleteTargetCoverage() {
        return !targetKeys.isEmpty() && targetKeys.stream().allMatch(tiles::containsKey);
    }

    private void settleVisualZoom() {
        if (state == null) {
            return;
        }
        animateToZoom(
                MapZoomLevel.nearest(state.visualBlocksPerPixel()),
                zoomAnchorX,
                zoomAnchorY);
    }

    private void animateToZoom(
            MapZoomLevel target,
            double pointerX,
            double pointerY) {
        zoomSettle.stop();
        zoomAnimation.stop();
        zoomAnimation.getKeyFrames().clear();
        zoomAnimation.setOnFinished(null);
        double start = state.visualBlocksPerPixel();
        double end = target.blocksPerPixel();
        state.commitZoom(target);
        if (Math.abs(start - end) < 0.000001) {
            draw();
            visualChanged.run();
            zoomTargetChanged.run();
            return;
        }

        int frames = Math.max(1, ZOOM_ANIMATION_MILLIS / 16);
        double startLog = Math.log(start);
        double endLog = Math.log(end);
        for (int frame = 1; frame <= frames; frame++) {
            double progress = (double) frame / frames;
            double eased = 1 - Math.pow(1 - progress, 3);
            double value = Math.exp(startLog + (endLog - startLog) * eased);
            zoomAnimation.getKeyFrames().add(new KeyFrame(
                    Duration.millis((double) ZOOM_ANIMATION_MILLIS * progress),
                    event -> {
                        state.zoomVisualAt(
                                value,
                                pointerX,
                                pointerY,
                                getWidth(),
                                getHeight());
                        draw();
                        visualChanged.run();
                    }));
        }
        zoomAnimation.setOnFinished(event -> {
            visualChanged.run();
            zoomTargetChanged.run();
        });
        zoomAnimation.playFromStart();
    }

    private void draw() {
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setImageSmoothing(isShowingTemporaryScale());
        graphics.setFill(Color.web("#20262b"));
        graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (state == null) {
            return;
        }
        drawTiles(graphics, false);
        drawPartialTiles(graphics);
        drawTiles(graphics, true);
        graphics.setStroke(Color.rgb(255, 255, 255, 0.08));
        for (MapTileKey key : tiles.keySet()) {
            MapTileBounds bounds = key.bounds();
            double size = bounds.blockWidth() / state.visualBlocksPerPixel();
            graphics.strokeRect(
                    Math.floor(state.screenXFor(bounds.minX(), canvas.getWidth())),
                    Math.floor(state.screenYFor(bounds.minZ(), canvas.getHeight())),
                    size,
                    size);
        }
        visibleMarkers().forEach(marker -> drawMarker(graphics, marker));
    }

    private void drawTiles(GraphicsContext graphics, boolean targetLevel) {
        for (Map.Entry<MapTileKey, Image> entry : tiles.entrySet()) {
            boolean isTarget = state.zoom() == entry.getKey().zoom();
            if (isTarget != targetLevel) {
                continue;
            }
            MapTileBounds bounds = entry.getKey().bounds();
            double x = state.screenXFor(bounds.minX(), canvas.getWidth());
            double y = state.screenYFor(bounds.minZ(), canvas.getHeight());
            double size = bounds.blockWidth() / state.visualBlocksPerPixel();
            graphics.drawImage(
                    entry.getValue(),
                    Math.floor(x),
                    Math.floor(y),
                    Math.ceil(size),
                    Math.ceil(size));
        }
    }

    private void drawPartialTiles(GraphicsContext graphics) {
        for (Map.Entry<MapTileKey, Image> entry : partialTiles.entrySet()) {
            MapTileBounds bounds = entry.getKey().bounds();
            double x = state.screenXFor(bounds.minX(), canvas.getWidth());
            double y = state.screenYFor(bounds.minZ(), canvas.getHeight());
            double size = bounds.blockWidth() / state.visualBlocksPerPixel();
            graphics.drawImage(
                    entry.getValue(),
                    Math.floor(x),
                    Math.floor(y),
                    Math.ceil(size),
                    Math.ceil(size));
        }
    }

    private void releaseFallbackIfCovered() {
        if (!hasCompleteTargetCoverage() || state == null) {
            return;
        }
        tiles.keySet().removeIf(key -> !targetKeys.contains(key));
        tileMarkers.keySet().removeIf(key -> !targetKeys.contains(key));
        partialTiles.clear();
        partialTileMarkers.clear();
    }

    private static double clampVisualZoom(double value) {
        return Math.max(MIN_VISUAL_ZOOM, Math.min(MAX_VISUAL_ZOOM, value));
    }

    private void drawMarker(GraphicsContext graphics, MapMarker marker) {
        double x = state.screenXFor(marker.x(), canvas.getWidth());
        double y = state.screenYFor(marker.z(), canvas.getHeight());
        graphics.setFill(markerColor(marker.type()));
        graphics.setStroke(Color.WHITE);
        graphics.setLineWidth(2);
        graphics.fillOval(x - MARKER_RADIUS, y - MARKER_RADIUS,
                MARKER_RADIUS * 2, MARKER_RADIUS * 2);
        graphics.strokeOval(x - MARKER_RADIUS, y - MARKER_RADIUS,
                MARKER_RADIUS * 2, MARKER_RADIUS * 2);
    }

    private MapMarker markerAt(double screenX, double screenY) {
        if (state == null) {
            return null;
        }
        for (MapMarker marker : visibleMarkers()) {
            double x = state.screenXFor(marker.x(), canvas.getWidth());
            double y = state.screenYFor(marker.z(), canvas.getHeight());
            if (Math.hypot(screenX - x, screenY - y) <= MARKER_RADIUS + 3) {
                return marker;
            }
        }
        return null;
    }

    private List<MapMarker> visibleMarkers() {
        List<MapMarker> markers = new ArrayList<>(fixedMarkers);
        List<MapMarker> portals = java.util.stream.Stream.concat(
                        tileMarkers.values().stream(),
                        partialTileMarkers.values().stream())
                .flatMap(List::stream)
                .filter(marker -> marker.type() == MapMarkerType.NETHER_PORTAL)
                .toList();
        markers.addAll(MapMarkerMerger.mergePortals(portals));
        return markers.stream().filter(this::isMarkerVisible).distinct().toList();
    }

    private boolean isMarkerVisible(MapMarker marker) {
        return switch (marker.type()) {
            case PLAYER -> showPlayer.get();
            case WORLD_SPAWN -> showSpawn.get();
            case NETHER_PORTAL -> showPortals.get();
        };
    }

    private static Color markerColor(MapMarkerType type) {
        return MapMarkerStyle.color(type);
    }

    private static String markerLabel(MapMarker marker) {
        String type = switch (marker.type()) {
            case PLAYER -> "玩家";
            case WORLD_SPAWN -> "世界出生点";
            case NETHER_PORTAL -> "下界传送门";
        };
        return type + " · X " + marker.x() + " · Z " + marker.z();
    }

    static Optional<String> markerTooltipText(MapMarker marker) {
        return marker == null ? Optional.empty() : Optional.of(markerLabel(marker));
    }

    private void showMarkerTooltip(
            MapMarker marker,
            double screenX,
            double screenY) {
        Optional<String> text = markerTooltipText(marker);
        if (text.isEmpty()) {
            hideMarkerTooltip();
            return;
        }
        String label = text.orElseThrow();
        if (!label.equals(markerTooltip.getText())) {
            markerTooltip.hide();
            markerTooltip.setText(label);
        }
        if (!markerTooltip.isShowing()) {
            markerTooltip.show(canvas, screenX + 12, screenY + 16);
        } else {
            markerTooltip.setAnchorX(screenX + 12);
            markerTooltip.setAnchorY(screenY + 16);
        }
    }

    private void hideMarkerTooltip() {
        markerTooltip.hide();
        markerTooltip.setText("");
    }
}
