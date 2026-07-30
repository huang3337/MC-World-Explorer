package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerMerger;
import com.mcworldexplorer.map.MapMarkerType;
import com.mcworldexplorer.map.MapDisplayZoom;
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
import java.util.function.Consumer;

public final class MapViewport extends Region {
    private static final double MARKER_RADIUS = 7;
    private static final double MIN_VISUAL_ZOOM = MapDisplayZoom.PIXELS_4.blocksPerPixel();
    private static final double MAX_VISUAL_ZOOM = MapDisplayZoom.BLOCKS_16.blocksPerPixel();
    private static final double WHEEL_ZOOM_DIVISOR = 240;
    private static final double CLICK_DRAG_THRESHOLD = 4;
    private static final int ZOOM_ANIMATION_MILLIS = 120;

    private final Canvas canvas = new Canvas();
    private final Map<MapTileKey, Image> tiles = new HashMap<>();
    private final Map<MapTileKey, List<MapMarker>> tileMarkers = new HashMap<>();
    private final Map<MapTileKey, Image> partialTiles = new HashMap<>();
    private final Map<MapTileKey, List<MapMarker>> partialTileMarkers = new HashMap<>();
    private final List<DisplayMapMarker> fixedMarkers = new ArrayList<>();
    private final Tooltip markerTooltip = new Tooltip();
    private final BooleanProperty showPlayer = new SimpleBooleanProperty(true);
    private final BooleanProperty showSpawn = new SimpleBooleanProperty(true);
    private final BooleanProperty showPortals = new SimpleBooleanProperty(true);
    private final PauseTransition zoomSettle = new PauseTransition(Duration.millis(150));
    private final Timeline zoomAnimation = new Timeline();
    private Set<MapTileKey> targetKeys = Set.of();
    private Set<MapTileKey> failedKeys = Set.of();
    private Set<MapTileKey> loadingKeys = Set.of();
    private MapViewportState state;
    private Runnable viewportChanged = () -> {
    };
    private Runnable visualChanged = () -> {
    };
    private Runnable zoomTargetChanged = () -> {
    };
    private Consumer<MapTileKey> retryRequested = ignored -> {
    };
    private double dragX;
    private double dragY;
    private double pressX;
    private double pressY;
    private double zoomAnchorX;
    private double zoomAnchorY;
    private boolean primaryPressed;
    private boolean dragging;
    private MapTileKey pressedFailedKey;
    private String highlightedPlayerIdentifier;

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
                DisplayMapMarker marker = markerAt(event.getX(), event.getY());
                if (marker != null) {
                    state.centerOn(marker.marker().x(), marker.marker().z());
                    draw();
                    viewportChanged.run();
                    return;
                }
                primaryPressed = true;
                dragging = false;
                pressX = event.getX();
                pressY = event.getY();
                dragX = event.getX();
                dragY = event.getY();
                pressedFailedKey = failedTileAt(event.getX(), event.getY());
                canvas.setCursor(pressedFailedKey == null ? Cursor.CLOSED_HAND : Cursor.HAND);
            }
        });
        canvas.setOnMouseDragged(event -> {
            hideMarkerTooltip();
            if (state == null || !event.isPrimaryButtonDown() || !primaryPressed) {
                return;
            }
            if (!dragging) {
                if (Math.hypot(event.getX() - pressX, event.getY() - pressY)
                        <= CLICK_DRAG_THRESHOLD) {
                    return;
                }
                dragging = true;
                pressedFailedKey = null;
                canvas.setCursor(Cursor.CLOSED_HAND);
            }
            state.panPixels(event.getX() - dragX, event.getY() - dragY);
            dragX = event.getX();
            dragY = event.getY();
            draw();
            viewportChanged.run();
        });
        canvas.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY
                    && primaryPressed
                    && !dragging
                    && isRetryClick(
                            pressedFailedKey,
                            failedTileAt(event.getX(), event.getY()),
                            event.getX() - pressX,
                            event.getY() - pressY)) {
                retryRequested.accept(pressedFailedKey);
            }
            primaryPressed = false;
            dragging = false;
            pressedFailedKey = null;
            canvas.setCursor(Cursor.DEFAULT);
        });
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
            DisplayMapMarker marker = markerAt(event.getX(), event.getY());
            MapTileKey failedKey = marker == null
                    ? failedTileAt(event.getX(), event.getY())
                    : null;
            Optional<String> text = marker == null
                    ? failedKey == null
                            ? Optional.empty()
                            : Optional.of("加载失败，点击重试")
                    : displayMarkerTooltipText(marker);
            showTooltip(text, event.getScreenX(), event.getScreenY());
            canvas.setCursor(marker == null && failedKey == null ? Cursor.DEFAULT : Cursor.HAND);
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

    public void setOnRetryRequested(Consumer<MapTileKey> retryRequested) {
        this.retryRequested = retryRequested == null ? ignored -> {
        } : retryRequested;
    }

    public void showTile(MapTileKey key, Image image, List<MapMarker> markers) {
        tiles.put(key, image);
        tileMarkers.put(key, List.copyOf(markers));
        partialTiles.remove(key);
        partialTileMarkers.remove(key);
        failedKeys = without(failedKeys, key);
        loadingKeys = without(loadingKeys, key);
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
        failedKeys = intersection(failedKeys, targetKeys);
        loadingKeys = intersection(loadingKeys, targetKeys);
        releaseFallbackIfCovered();
        draw();
    }

    public void setTileLoadStates(Set<MapTileKey> failedKeys, Set<MapTileKey> loadingKeys) {
        this.failedKeys = intersection(Set.copyOf(failedKeys), targetKeys);
        this.loadingKeys = intersection(Set.copyOf(loadingKeys), targetKeys);
        if (pressedFailedKey != null && !this.failedKeys.contains(pressedFailedKey)) {
            pressedFailedKey = null;
        }
        draw();
    }

    public void clearTiles() {
        tiles.clear();
        tileMarkers.clear();
        partialTiles.clear();
        partialTileMarkers.clear();
        targetKeys = Set.of();
        failedKeys = Set.of();
        loadingKeys = Set.of();
        primaryPressed = false;
        dragging = false;
        pressedFailedKey = null;
        highlightedPlayerIdentifier = null;
        hideMarkerTooltip();
        draw();
    }

    public void setFixedMarkers(List<DisplayMapMarker> markers) {
        hideMarkerTooltip();
        fixedMarkers.clear();
        fixedMarkers.addAll(markers);
        draw();
    }

    public void highlightPlayer(String identifier) {
        highlightedPlayerIdentifier = identifier;
        draw();
    }

    public void clearPlayerHighlight() {
        highlightedPlayerIdentifier = null;
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
            animateToZoom(state.displayZoom().zoomIn(), getWidth() / 2, getHeight() / 2);
        }
    }

    public void zoomOut() {
        if (state != null) {
            animateToZoom(state.displayZoom().zoomOut(), getWidth() / 2, getHeight() / 2);
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
                state.visualBlocksPerPixel() - state.displayZoom().blocksPerPixel()) > 0.000001
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
                MapDisplayZoom.nearest(state.visualBlocksPerPixel()),
                zoomAnchorX,
                zoomAnchorY);
    }

    private void animateToZoom(
            MapDisplayZoom target,
            double pointerX,
            double pointerY) {
        zoomSettle.stop();
        zoomAnimation.stop();
        zoomAnimation.getKeyFrames().clear();
        zoomAnimation.setOnFinished(null);
        double start = state.visualBlocksPerPixel();
        double end = target.blocksPerPixel();
        if (isSettledAtZoom(state.displayZoom(), start, target)) {
            return;
        }
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
        drawFailedTiles(graphics);
        drawLoadingTiles(graphics);
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

    private void drawFailedTiles(GraphicsContext graphics) {
        for (MapTileKey key : failedKeys) {
            MapTileBounds bounds = key.bounds();
            double x = state.screenXFor(bounds.minX(), canvas.getWidth());
            double y = state.screenYFor(bounds.minZ(), canvas.getHeight());
            double size = bounds.blockWidth() / state.visualBlocksPerPixel();
            if (!tiles.containsKey(key) && !partialTiles.containsKey(key)) {
                graphics.setFill(Color.rgb(12, 15, 18, 0.92));
                graphics.fillRect(Math.floor(x), Math.floor(y), Math.ceil(size), Math.ceil(size));
            }
            graphics.save();
            graphics.beginPath();
            graphics.rect(Math.floor(x), Math.floor(y), Math.ceil(size), Math.ceil(size));
            graphics.closePath();
            graphics.clip();
            graphics.setFill(Color.rgb(190, 34, 42, 0.18));
            graphics.fillRect(Math.floor(x), Math.floor(y), Math.ceil(size), Math.ceil(size));
            graphics.setStroke(Color.rgb(235, 72, 82, 0.72));
            graphics.setLineWidth(2);
            double step = 18;
            for (double offset = -size; offset < size * 2; offset += step) {
                graphics.strokeLine(x + offset, y + size, x + offset + size, y);
            }
            graphics.restore();
            graphics.setStroke(Color.rgb(239, 75, 85, 0.95));
            graphics.setLineWidth(2);
            graphics.strokeRect(Math.floor(x) + 1, Math.floor(y) + 1,
                    Math.max(0, Math.ceil(size) - 2), Math.max(0, Math.ceil(size) - 2));
        }
    }

    private void drawLoadingTiles(GraphicsContext graphics) {
        for (MapTileKey key : loadingKeys) {
            MapTileBounds bounds = key.bounds();
            double x = state.screenXFor(bounds.minX(), canvas.getWidth());
            double y = state.screenYFor(bounds.minZ(), canvas.getHeight());
            double size = bounds.blockWidth() / state.visualBlocksPerPixel();
            if (!tiles.containsKey(key) && !partialTiles.containsKey(key)) {
                graphics.setFill(Color.rgb(12, 15, 18, 0.75));
                graphics.fillRect(Math.floor(x), Math.floor(y), Math.ceil(size), Math.ceil(size));
            }
            graphics.setStroke(Color.rgb(255, 255, 255, 0.75));
            graphics.setLineWidth(2);
            graphics.strokeRect(Math.floor(x) + 2, Math.floor(y) + 2,
                    Math.max(0, Math.ceil(size) - 4), Math.max(0, Math.ceil(size) - 4));
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

    private void drawMarker(GraphicsContext graphics, DisplayMapMarker displayMarker) {
        MapMarker marker = displayMarker.marker();
        double x = state.screenXFor(marker.x(), canvas.getWidth());
        double y = state.screenYFor(marker.z(), canvas.getHeight());
        if (displayMarker.identifier()
                .filter(identifier -> identifier.equals(highlightedPlayerIdentifier))
                .isPresent()) {
            graphics.setFill(Color.TRANSPARENT);
            graphics.setStroke(Color.rgb(68, 199, 103, 0.95));
            graphics.setLineWidth(3);
            graphics.strokeOval(
                    x - MARKER_RADIUS - 6,
                    y - MARKER_RADIUS - 6,
                    (MARKER_RADIUS + 6) * 2,
                    (MARKER_RADIUS + 6) * 2);
        }
        graphics.setFill(markerColor(marker.type()));
        graphics.setStroke(Color.WHITE);
        graphics.setLineWidth(2);
        graphics.fillOval(x - MARKER_RADIUS, y - MARKER_RADIUS,
                MARKER_RADIUS * 2, MARKER_RADIUS * 2);
        graphics.strokeOval(x - MARKER_RADIUS, y - MARKER_RADIUS,
                MARKER_RADIUS * 2, MARKER_RADIUS * 2);
    }

    private DisplayMapMarker markerAt(double screenX, double screenY) {
        if (state == null) {
            return null;
        }
        for (DisplayMapMarker displayMarker : visibleMarkers()) {
            MapMarker marker = displayMarker.marker();
            double x = state.screenXFor(marker.x(), canvas.getWidth());
            double y = state.screenYFor(marker.z(), canvas.getHeight());
            if (Math.hypot(screenX - x, screenY - y) <= MARKER_RADIUS + 3) {
                return displayMarker;
            }
        }
        return null;
    }

    private MapTileKey failedTileAt(double screenX, double screenY) {
        if (state == null) {
            return null;
        }
        for (MapTileKey key : failedKeys) {
            MapTileBounds bounds = key.bounds();
            double x = state.screenXFor(bounds.minX(), canvas.getWidth());
            double y = state.screenYFor(bounds.minZ(), canvas.getHeight());
            double size = bounds.blockWidth() / state.visualBlocksPerPixel();
            if (screenX >= x && screenX <= x + size
                    && screenY >= y && screenY <= y + size) {
                return key;
            }
        }
        return null;
    }

    static boolean isRetryClick(
            MapTileKey pressed,
            MapTileKey released,
            double deltaX,
            double deltaY) {
        return pressed != null
                && pressed.equals(released)
                && Math.hypot(deltaX, deltaY) <= CLICK_DRAG_THRESHOLD;
    }

    static boolean isSettledAtZoom(
            MapDisplayZoom current,
            double visualBlocksPerPixel,
            MapDisplayZoom target) {
        return current == target
                && Math.abs(visualBlocksPerPixel - target.blocksPerPixel()) <= 0.000001;
    }

    private List<DisplayMapMarker> visibleMarkers() {
        List<DisplayMapMarker> markers = new ArrayList<>(fixedMarkers);
        List<MapMarker> portals = java.util.stream.Stream.concat(
                        tileMarkers.values().stream(),
                        partialTileMarkers.values().stream())
                .flatMap(List::stream)
                .filter(marker -> marker.type() == MapMarkerType.NETHER_PORTAL)
                .toList();
        MapMarkerMerger.mergePortals(portals).stream()
                .map(DisplayMapMarker::standard)
                .forEach(markers::add);
        return markers.stream().filter(this::isMarkerVisible).distinct().toList();
    }

    private boolean isMarkerVisible(DisplayMapMarker displayMarker) {
        return switch (displayMarker.marker().type()) {
            case PLAYER -> showPlayer.get();
            case WORLD_SPAWN -> showSpawn.get();
            case NETHER_PORTAL -> showPortals.get();
        };
    }

    private static Color markerColor(MapMarkerType type) {
        return MapMarkerStyle.color(type);
    }

    static Optional<String> markerTooltipText(MapMarker marker) {
        return marker == null
                ? Optional.empty()
                : Optional.of(DisplayMapMarker.standard(marker).tooltipText());
    }

    static Optional<String> displayMarkerTooltipText(DisplayMapMarker marker) {
        return marker == null ? Optional.empty() : Optional.of(marker.tooltipText());
    }

    private void showTooltip(
            Optional<String> text,
            double screenX,
            double screenY) {
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

    private static Set<MapTileKey> intersection(
            Set<MapTileKey> left,
            Set<MapTileKey> right) {
        return left.stream()
                .filter(right::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<MapTileKey> without(Set<MapTileKey> keys, MapTileKey removed) {
        return keys.stream()
                .filter(key -> !key.equals(removed))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
