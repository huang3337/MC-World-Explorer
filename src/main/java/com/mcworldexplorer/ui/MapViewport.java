package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerMerger;
import com.mcworldexplorer.map.MapMarkerType;
import com.mcworldexplorer.map.MapDisplayZoom;
import com.mcworldexplorer.map.MapTileBounds;
import com.mcworldexplorer.map.MapTileKey;
import com.mcworldexplorer.map.MapViewportState;
import com.mcworldexplorer.map.MapZoomLevel;
import com.mcworldexplorer.storage.SavedPlaces.Position;
import com.mcworldexplorer.viewer.ViewerRangePreset;
import com.mcworldexplorer.viewer.ViewerPerformanceProfile;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class MapViewport extends Region {
    private static final double MARKER_RADIUS = 7;
    private static final double MIN_VISUAL_ZOOM = MapDisplayZoom.PIXELS_4.blocksPerPixel();
    private static final double MAX_VISUAL_ZOOM = MapDisplayZoom.BLOCKS_16.blocksPerPixel();
    private static final double WHEEL_ZOOM_DIVISOR = 240;
    static final double CLICK_DRAG_THRESHOLD = 4;
    private static final double PLACE_RADIUS = 4;
    private static final double PLACE_STROKE_WIDTH = 1.5;
    private static final double PLACE_OUTER_RADIUS = PLACE_RADIUS + PLACE_STROKE_WIDTH / 2;

    private boolean displayBatch, displayDirty;
    public void beginDisplayBatch() { displayBatch = true; }
    public void endDisplayBatch() {
        displayBatch = false;
        if (displayDirty) { displayDirty = false; draw(); }
    }
    public Map<MapTileKey, Image> completeTiles() {
        var ready = new HashMap<MapTileKey, Image>();
        for (var key : validatedKeys) if (tiles.containsKey(key)) ready.put(key, tiles.get(key));
        return Map.copyOf(ready);
    }
    public boolean isRevalidatingVisibleTiles() {
        return targetKeys.stream().anyMatch(k -> tiles.containsKey(k) && !validatedKeys.contains(k));
    }
    private void trimDisplayImages() {
        // At most 80 image slots: 64 current + 16 temporary fallback, including partials.
        var old = new LinkedHashSet<>(tiles.keySet());
        old.addAll(partialTiles.keySet());
        for (var key : old) if ((tiles.size() + partialTiles.size() > 80 || estimatedDisplayBytes() > (20L << 20)) && !targetKeys.contains(key)) {
            tiles.remove(key); tileMarkers.remove(key);
            partialTiles.remove(key); partialTileMarkers.remove(key); incompleteReasons.remove(key);
        }
    }
    public long estimatedDisplayBytes() {
        return (long)(tiles.size() + partialTiles.size()) * (256 * 256 * 4 + 512)
                + tileMarkers.values().stream().mapToLong(v -> v.size() * 192L).sum()
                + partialTileMarkers.values().stream().mapToLong(v -> v.size() * 192L).sum()
                + incompleteReasons.values().stream().mapToLong(v -> 64L + v.length() * 2L).sum();
    }
    public void discardTile(MapTileKey key) {
        validatedKeys.remove(key); tiles.remove(key); tileMarkers.remove(key); partialTiles.remove(key); partialTileMarkers.remove(key); incompleteReasons.remove(key); draw();
    }
    private void enforceDisplayBudget(MapTileKey key) {
        trimDisplayImages();
        if (estimatedDisplayBytes() > (20L << 20)) {
            discardTile(key); throw new IllegalStateException("Map display budget exceeded; reduce viewport size");
        }
    }
    private final Canvas canvas = new Canvas();
    final PlaceLineOverlay placeLines = new PlaceLineOverlay(this, canvas);
    private final Map<MapTileKey, Image> tiles = new java.util.LinkedHashMap<>(16, .75f, true);
    private final Set<MapTileKey> validatedKeys = new LinkedHashSet<>();
    private final Map<MapTileKey, List<MapMarker>> tileMarkers = new HashMap<>();
    private final Map<MapTileKey, Image> partialTiles = new HashMap<>();
    private final Map<MapTileKey, List<MapMarker>> partialTileMarkers = new HashMap<>();
    private List<DisplayMapMarker> fixedMarkers = List.of();
    private final Tooltip markerTooltip = new Tooltip();
    private final BooleanProperty showPlayer = new SimpleBooleanProperty(true);
    private final BooleanProperty showSpawn = new SimpleBooleanProperty(true);
    private final BooleanProperty showPortals = new SimpleBooleanProperty(true);
    private final PauseTransition zoomSettle = new PauseTransition(Duration.millis(150));
    private Set<MapTileKey> targetKeys = Set.of();
    private Set<MapTileKey> failedKeys = Set.of();
    private final Map<MapTileKey, String> incompleteReasons = new HashMap<>();
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
    private Consumer<ChunkRectangle> viewerSelectionLocked = ignored -> {
    };
    private Runnable viewerEscapeRequested = () -> {
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
    private boolean viewerSelectionMode;
    private boolean viewerFreeSelection;
    record PlaceHit(Position position, DisplayMapMarker original) {}
    record BlockClick(long x, long z, PlaceHit marker, com.mcworldexplorer.storage.SavedPlaces.Link line,
                      double screenX, double screenY) {}
    private Consumer<BlockClick> blockMenuRequested = ignored -> {};
    private boolean secondaryPressed;
    private boolean secondaryDragged;
    private long pressedBlockX;
    private long pressedBlockZ;
    private PlaceHit pressedPlace;
    private Map<Position, String> namedPlaces = Map.of();
    private ChunkRectangle viewerDragStart;
    private ViewerPerformanceProfile viewerProfile = ViewerPerformanceProfile.BASIC;
    private Consumer<ChunkRectangle> viewerSelectionChanged = ignored -> {};
    private ViewerRangePreset viewerRangePreset = ViewerRangePreset.THREE;
    private final ViewerSelectionState viewerSelection = new ViewerSelectionState();
    private Set<ChunkRectangle> viewerCachedAreas = Set.of();
    private String viewerCachedAreaContext = "";
    private double lastPointerX;
    private double lastPointerY;
    private boolean pointerInside;

    public MapViewport() {
        getChildren().addAll(canvas, placeLines.measurement);
        setMinSize(0, 0);
        setFocusTraversable(true);
        showPlayer.addListener((observable, oldValue, newValue) -> draw());
        showSpawn.addListener((observable, oldValue, newValue) -> draw());
        showPortals.addListener((observable, oldValue, newValue) -> draw());
        zoomSettle.setOnFinished(event -> settleVisualZoom());
        canvas.setOnMousePressed(event -> {
            hideMarkerTooltip();
            if (event.getButton() == MouseButton.SECONDARY) {
                if (state == null || primaryPressed) return;
                secondaryPressed = true;
                secondaryDragged = false;
                pressX = dragX = event.getX();
                pressY = dragY = event.getY();
                pressedBlockX = blockXAt(pressX);
                pressedBlockZ = blockZAt(pressY);
                pressedPlace = placeAt(pressX, pressY, 0);
                return;
            }
            if (event.getButton() == MouseButton.PRIMARY) {
                secondaryPressed = false;
                primaryPressed = true;
                dragging = false;
                pressX = event.getX();
                pressY = event.getY();
                dragX = event.getX();
                dragY = event.getY();
                pressedFailedKey = viewerSelectionMode
                        ? null
                        : failedTileAt(event.getX(), event.getY());
                if (viewerSelectionMode && viewerFreeSelection && viewerSelection.canSelect() && state != null) {
                            viewerDragStart = viewerSelectionAt(state, pressX, pressY,
                            canvas.getWidth(), canvas.getHeight(), ViewerRangePreset.ONE);
                    updateViewerCandidate(pressX, pressY);
                    requestFocus();
                    draw();
                }
                canvas.setCursor(viewerSelectionMode && viewerFreeSelection && viewerSelection.canSelect() ? Cursor.CROSSHAIR
                        : pressedFailedKey == null ? Cursor.CLOSED_HAND : Cursor.HAND);
            }
        });
        canvas.setOnMouseDragged(event -> {
            trackPointer(event.getX(), event.getY());
            hideMarkerTooltip();
            if (state != null && secondaryPressed && event.isSecondaryButtonDown()) {
                if (Math.hypot(event.getX() - pressX, event.getY() - pressY) > CLICK_DRAG_THRESHOLD) {
                    secondaryDragged = true;
                }
                if (secondaryDragged) {
                    state.panPixels(event.getX() - dragX, event.getY() - dragY);
                    dragX = event.getX();
                    dragY = event.getY();
                    canvas.setCursor(Cursor.CLOSED_HAND);
                    draw();
                    viewportChanged.run();
                }
                return;
            }
            if (state == null || !event.isPrimaryButtonDown() || !primaryPressed) {
                return;
            }
            if (viewerSelectionMode && viewerFreeSelection && viewerDragStart != null) {
                dragging = true;
                updateViewerCandidate(event.getX(), event.getY());
                draw();
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
            updateViewerCandidate(event.getX(), event.getY());
            draw();
            viewportChanged.run();
        });
        canvas.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                boolean click = secondaryPressed && !secondaryDragged && state != null
                        && canvas.contains(event.getX(), event.getY())
                        && Math.hypot(event.getX() - pressX, event.getY() - pressY) <= CLICK_DRAG_THRESHOLD;
                secondaryPressed = false;
                canvas.setCursor(viewerSelectionCursor());
                if (click) blockMenuRequested.accept(new BlockClick(
                        pressedBlockX, pressedBlockZ, pressedPlace,
                        pressedPlace == null && !viewerSelectionMode ? placeLines.hit(pressX, pressY) : null,
                        event.getScreenX(), event.getScreenY()));
                event.consume();
                return;
            }
            if (event.getButton() == MouseButton.PRIMARY && primaryPressed
                    && viewerSelectionMode && viewerFreeSelection && viewerDragStart != null) {
                updateViewerCandidate(event.getX(), event.getY());
            }
            if (event.getButton() == MouseButton.PRIMARY
                    && primaryPressed
                    && (!dragging || viewerFreeSelection)
                    && viewerSelectionMode
                    && viewerSelection.canSelect()
                    && viewerSelection.candidate() != null
                    && viewerProfile.allows(viewerSelection.candidate())) {
                viewerSelection.lock(viewerSelection.candidate());
                viewerSelectionLocked.accept(viewerSelection.locked());
                requestFocus();
            } else if (event.getButton() == MouseButton.PRIMARY
                    && primaryPressed
                    && !dragging
                    && isRetryClick(
                            pressedFailedKey,
                            failedTileAt(event.getX(), event.getY()),
                            event.getX() - pressX,
                            event.getY() - pressY)) {
                retryRequested.accept(pressedFailedKey);
            }
            secondaryPressed = false;
            viewerDragStart = null;
            primaryPressed = false;
            dragging = false;
            pressedFailedKey = null;
            canvas.setCursor(viewerSelectionCursor());
            draw();
        });
        canvas.setOnScroll(event -> {
            trackPointer(event.getX(), event.getY());
            hideMarkerTooltip();
            if (state == null || event.getDeltaY() == 0 || (viewerFreeSelection && primaryPressed)) {
                return;
            }
            wheelZoom(event.getX(), event.getY(), event.getDeltaY());
            event.consume();
        });
        canvas.setOnMouseEntered(event -> {
            trackPointer(event.getX(), event.getY());
            visualChanged.run();
        });
        canvas.setOnMouseMoved(event -> {
            trackPointer(event.getX(), event.getY());
            visualChanged.run();
            if (viewerSelectionMode) {
                updateViewerCandidate(event.getX(), event.getY());
                Optional<String> cachedText = viewerSelection.candidate() != null
                        && viewerCachedAreas.contains(viewerSelection.candidate())
                        ? Optional.of(viewerCachedTooltip(viewerSelection.candidate()))
                        : Optional.empty();
                showTooltip(cachedText, event.getScreenX(), event.getScreenY());
                canvas.setCursor(viewerSelectionCursor());
                draw();
                return;
            }
            PlaceHit hit = placeAt(event.getX(), event.getY(), 0);
            DisplayMapMarker marker = hit == null ? null : hit.original();
            var line = marker == null ? placeLines.hit(event.getX(), event.getY()) : null;
            placeLines.hover(line);
            if (line != null) {
                showTooltip(Optional.of(line.distances()), event.getScreenX(), event.getScreenY());
                canvas.setCursor(Cursor.HAND);
                return;
            }
            MapTileKey failedKey = marker == null
                    ? failedTileAt(event.getX(), event.getY())
                    : null;
            Optional<String> text = marker == null
                    ? failedKey == null
                            ? Optional.empty()
                            : Optional.of(incompleteReasons.getOrDefault(failedKey, "加载失败，点击重试"))
                    : displayMarkerTooltipText(marker);
            if (hit != null && namedPlaces.containsKey(hit.position())) {
                String name = namedPlaces.get(hit.position());
                if (marker == null) text = Optional.of(name);
                else if (!name.equals(marker.placeName())) text = Optional.of(name + "\n" + marker.tooltipText());
            }
            showTooltip(text, event.getScreenX(), event.getScreenY());
            canvas.setCursor(hit == null && failedKey == null ? Cursor.DEFAULT : Cursor.HAND);
        });
        canvas.setOnMouseExited(event -> {
            placeLines.hover(null);
            pointerInside = false;
            visualChanged.run();
            hideMarkerTooltip();
            if (viewerSelectionMode && viewerSelection.canSelect() && !primaryPressed) {
                viewerSelection.clearCandidate();
                draw();
            }
            canvas.setCursor(viewerSelectionCursor());
        });
        setOnKeyPressed(event -> {
            if (viewerSelectionMode && event.getCode() == KeyCode.ESCAPE) {
                viewerEscapeRequested.run();
                event.consume();
            }
        });
    }

    public void setViewportState(MapViewportState state) {
        hideMarkerTooltip();
        zoomSettle.stop();
        this.state = state;
        viewerSelection.clear();
        clearTiles();
        draw();
    }

    void wheelZoom(double x, double y, double delta) {
        if (state == null || delta == 0) return;
        zoomAnchorX = x;
        zoomAnchorY = y;
        double next = clampVisualZoom(state.visualBlocksPerPixel() * Math.pow(2, -delta / WHEEL_ZOOM_DIVISOR));
        state.zoomVisualAt(next, x, y, getWidth(), getHeight());
        updateViewerCandidate(x, y);
        draw();
        visualChanged.run();
        zoomSettle.playFromStart();
    }

    void panLineView(double dx, double dy) {
        state.panPixels(dx, dy);
        draw();
        viewportChanged.run();
    }

    void trackPointer(double x, double y) {
        lastPointerX = x;
        lastPointerY = y;
        pointerInside = x >= 0 && y >= 0 && x < getWidth() && y < getHeight();
    }

    public String pointerCoordinates() {
        if (state == null || !pointerInside) return "鼠标坐标 —";
        return "鼠标 X " + blockXAt(lastPointerX) + " · Z " + blockZAt(lastPointerY);
    }

    long blockXAt(double x) {
        return (long) Math.floor(state.worldXAt(x, getWidth()));
    }

    long blockZAt(double y) {
        return (long) Math.floor(state.worldZAt(y, getHeight()));
    }

    void setOnBlockMenuRequested(Consumer<BlockClick> handler) {
        blockMenuRequested = java.util.Objects.requireNonNull(handler);
    }

    void setNamedPlaces(Map<Position, String> places) {
        if (namedPlaces.equals(places)) return;
        namedPlaces = Map.copyOf(places);
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

    public void setOnViewerSelectionLocked(
            Consumer<ChunkRectangle> viewerSelectionLocked) {
        this.viewerSelectionLocked = viewerSelectionLocked == null ? ignored -> {
        } : viewerSelectionLocked;
    }

    public void setOnViewerEscapeRequested(Runnable viewerEscapeRequested) {
        this.viewerEscapeRequested = viewerEscapeRequested == null ? () -> {
        } : viewerEscapeRequested;
    }

    public void setViewerSelectionMode(boolean enabled) {
        placeLines.cancel();
        placeLines.hover(null);
        primaryPressed = false;
        secondaryPressed = false;
        viewerDragStart = null;
        viewerSelectionMode = enabled;
        if (!enabled) {
            viewerSelection.clear();
            hideMarkerTooltip();
        } else {
            requestFocus();
            updateViewerCandidateFromPointer();
        }
        canvas.setCursor(viewerSelectionCursor());
        draw();
    }

    public boolean isViewerSelectionMode() {
        return viewerSelectionMode;
    }

    public void setOnViewerSelectionChanged(Consumer<ChunkRectangle> changed) {
        viewerSelectionChanged = java.util.Objects.requireNonNull(changed);
    }

    public void setViewerFreeSelection(boolean free, ViewerPerformanceProfile profile) {
        viewerFreeSelection = free;
        viewerProfile = java.util.Objects.requireNonNull(profile);
        clearViewerLockedSelection();
    }

    public void setViewerRangePreset(ViewerRangePreset preset) {
        viewerRangePreset = java.util.Objects.requireNonNull(preset, "preset");
        clearViewerLockedSelection();
        draw();
    }

    public void clearViewerLockedSelection() {
        primaryPressed = false;
        secondaryPressed = false;
        viewerDragStart = null;
        viewerSelection.clear();
        updateViewerCandidateFromPointer();
        canvas.setCursor(viewerSelectionCursor());
        draw();
    }

    public void lockViewerSelection(ChunkRectangle selection) {
        viewerSelection.lock(selection);
        canvas.setCursor(viewerSelectionCursor());
        draw();
    }

    public Optional<ChunkRectangle> viewerLockedSelection() {
        return Optional.ofNullable(viewerSelection.locked());
    }

    public void setViewerTaskRunning(boolean running) {
        if (running) {
            viewerSelection.beginLoading();
        } else {
            viewerSelection.finishLoading(false);
        }
        draw();
    }

    public void markViewerSelectionLoaded() {
        viewerSelection.finishLoading(true);
        draw();
    }

    public void invalidateViewerLoadedSelection() {
        viewerSelection.invalidateLoaded();
        draw();
    }

    private Cursor viewerSelectionCursor() {
        return viewerSelectionMode && viewerSelection.canSelect() ? Cursor.CROSSHAIR : Cursor.DEFAULT;
    }

    public void setViewerCachedAreas(Iterable<ChunkRectangle> areas) {
        LinkedHashSet<ChunkRectangle> copy = new LinkedHashSet<>();
        if (areas != null) {
            areas.forEach(copy::add);
        }
        viewerCachedAreas = Set.copyOf(copy);
        draw();
    }

    public void setViewerCachedAreaContext(String context) {
        viewerCachedAreaContext = context == null ? "" : context.strip();
    }

    private String viewerCachedTooltip(ChunkRectangle area) {
        String prefix = viewerCachedAreaContext.isEmpty()
                ? "已缓存三维区域"
                : viewerCachedAreaContext + " · 已缓存，可立即打开";
        return String.format(
                "%s · 区块 X %d..%d · Z %d..%d",
                prefix, area.minX(), area.maxX(), area.minZ(), area.maxZ());
    }

    public void showTile(MapTileKey key, Image image, List<MapMarker> markers) {
        incompleteReasons.remove(key);
        validatedKeys.add(key);
        tiles.put(key, image);
        if (markers.isEmpty()) tileMarkers.remove(key);
        else tileMarkers.put(key, List.copyOf(markers));
        partialTiles.remove(key);
        partialTileMarkers.remove(key);
        failedKeys = without(failedKeys, key);
        loadingKeys = without(loadingKeys, key);
        releaseFallbackIfCovered();
        enforceDisplayBudget(key);
        draw();
    }

    public void showIncompleteTile(MapTileKey key, Image image, List<MapMarker> markers, String reason) {
        tiles.remove(key);
        validatedKeys.remove(key);
        tileMarkers.remove(key);
        incompleteReasons.put(key, reason);
        showPartialTile(key, image, markers);
    }

    public void showPartialTile(MapTileKey key, Image image, List<MapMarker> markers) {
        // A preview is not a validated replacement for a retained complete image.
        // Terminal success or failure owns replacement; new regions still stream partials.
        if (tiles.containsKey(key) || !targetKeys.contains(key)) {
            return;
        }
        partialTiles.put(key, image);
        if (markers.isEmpty()) partialTileMarkers.remove(key);
        else partialTileMarkers.put(key, List.copyOf(markers));
        enforceDisplayBudget(key);
        draw();
    }

    public void setTargetTiles(Iterable<MapTileKey> keys) {
        Map<MapTileKey, Boolean> requested = new HashMap<>();
        keys.forEach(key -> requested.put(key, true));
        targetKeys = Set.copyOf(requested.keySet());
        validatedKeys.retainAll(targetKeys);
        if (targetKeys.isEmpty()) {
            return;
        }
        var context = targetKeys.iterator().next();
        tiles.keySet().removeIf(k -> !sameContext(k, context));
        tileMarkers.keySet().retainAll(tiles.keySet());
        for (var key : targetKeys) tiles.get(key); // Touch recent visible images for bounded eviction.
        partialTiles.keySet().removeIf(key -> !retainPartialFallback(key));
        partialTileMarkers.keySet().retainAll(partialTiles.keySet());
        incompleteReasons.keySet().removeIf(key -> !targetKeys.contains(key));
        failedKeys = intersection(failedKeys, targetKeys);
        loadingKeys = intersection(loadingKeys, targetKeys);
        releaseFallbackIfCovered();
        trimDisplayImages();
        draw();
    }

    private static boolean sameContext(MapTileKey a, MapTileKey b) {
        return a.worldId().equals(b.worldId()) && a.dimensionId().equals(b.dimensionId())
                && a.layer().equals(b.layer()) && a.renderVersion().equals(b.renderVersion());
    }

    private boolean retainPartialFallback(MapTileKey old) {
        if (targetKeys.contains(old)) return true;
        if (incompleteReasons.containsKey(old)) return false;
        var b = old.bounds();
        return targetKeys.stream().anyMatch(k -> old.worldId().equals(k.worldId())
                && old.dimensionId().equals(k.dimensionId()) && old.layer().equals(k.layer())
                && old.renderVersion().equals(k.renderVersion())
                && b.minX() < k.bounds().maxXExclusive() && b.maxXExclusive() > k.bounds().minX()
                && b.minZ() < k.bounds().maxZExclusive() && b.maxZExclusive() > k.bounds().minZ());
    }

    public void setTileLoadStates(Set<MapTileKey> failedKeys, Set<MapTileKey> loadingKeys) {
        this.failedKeys = intersection(Set.copyOf(failedKeys), targetKeys);
        for (var key : this.failedKeys) if (!validatedKeys.contains(key)) {
            tiles.remove(key); tileMarkers.remove(key);
        }
        this.loadingKeys = intersection(Set.copyOf(loadingKeys), targetKeys);
        if (pressedFailedKey != null && !this.failedKeys.contains(pressedFailedKey)) {
            pressedFailedKey = null;
        }
        draw();
    }

    public void clearTiles() {
        placeLines.cancel();
        secondaryPressed = false;
        viewerDragStart = null;
        tiles.clear();
        validatedKeys.clear();
        tileMarkers.clear();
        partialTiles.clear();
        partialTileMarkers.clear();
        incompleteReasons.clear();
        targetKeys = Set.of();
        failedKeys = Set.of();
        loadingKeys = Set.of();
        primaryPressed = false;
        dragging = false;
        pressedFailedKey = null;
        highlightedPlayerIdentifier = null;
        // Map tile invalidation must not unlock a three-dimensional selection.
        viewerSelection.clearCandidate();
        hideMarkerTooltip();
        draw();
    }

    public void setFixedMarkers(List<DisplayMapMarker> markers) {
        hideMarkerTooltip();
        fixedMarkers = markers.stream().distinct().toList();
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
            changeZoomTo(state.displayZoom().zoomIn(), getWidth() / 2, getHeight() / 2);
        }
    }

    public void zoomOut() {
        if (state != null) {
            changeZoomTo(state.displayZoom().zoomOut(), getWidth() / 2, getHeight() / 2);
        }
    }

    public void resetView() {
        if (state != null) {
            hideMarkerTooltip();
            zoomSettle.stop();
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

    public BufferedImage snapshotTerrain() {
        Canvas terrain = new Canvas(canvas.getWidth(), canvas.getHeight());
        GraphicsContext graphics = terrain.getGraphicsContext2D();
        drawBackground(graphics);
        graphics.setImageSmoothing(false);
        for (var entry : tiles.entrySet()) {
            if (targetKeys.contains(entry.getKey()) && validatedKeys.contains(entry.getKey())) {
                drawTile(graphics, entry.getKey(), entry.getValue());
            }
        }
        WritableImage snapshot = terrain.snapshot(null, null);
        BufferedImage output = new BufferedImage(
                (int) snapshot.getWidth(),
                (int) snapshot.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
        snapshot.getPixelReader().getPixels(0, 0, output.getWidth(), output.getHeight(),
                PixelFormat.getIntArgbInstance(), pixels, 0, output.getWidth());
        return output;
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(Math.max(1, getWidth()));
        canvas.setHeight(Math.max(1, getHeight()));
        draw();
    }

    public boolean isShowingTemporaryScale() {
        return state != null && !hasCompleteTargetCoverage() && tiles.keySet().stream()
                .anyMatch(key -> key.zoom() != state.zoom() && retainPartialFallback(key));
    }

    public boolean hasCompleteTargetCoverage() {
        return !targetKeys.isEmpty() && targetKeys.stream().allMatch(k -> validatedKeys.contains(k) && tiles.containsKey(k));
    }

    private void settleVisualZoom() {
        if (state == null) return;
        // Commit only the raster detail level. The camera stays at the exact wheel ratio.
        state.commitZoom(MapDisplayZoom.nearest(state.visualBlocksPerPixel()));
        visualChanged.run();
        zoomTargetChanged.run();
    }

    private void changeZoomTo(MapDisplayZoom target, double pointerX, double pointerY) {
        zoomSettle.stop();
        state.zoomAt(target, pointerX, pointerY, getWidth(), getHeight());
        draw();
        visualChanged.run();
        zoomTargetChanged.run();
    }

    void draw() {
        if (displayBatch) { displayDirty = true; return; }
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setImageSmoothing(isShowingTemporaryScale());
        drawBackground(graphics);
        if (state == null) {
            return;
        }
        drawTiles(graphics, false);
        drawPartialTiles(graphics, false);
        drawPartialTiles(graphics, true);
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
        var visible = visibleMarkers();
        placeLines.draw(graphics, visible);
        Set<Position> originalPositions = new java.util.HashSet<>();
        for (var marker : visible) {
            drawMarker(graphics, marker);
            originalPositions.add(marker.position());
        }
        drawNamedPlaces(graphics, originalPositions);
        drawViewerSelections(graphics);
    }

    private void drawNamedPlaces(GraphicsContext graphics, Set<Position> originalPositions) {
        for (Position place : namedPlaces.keySet()) {
            if (originalPositions.contains(place)) continue;
            double x = placeScreenX(place);
            double y = placeScreenY(place);
            if (x < -PLACE_OUTER_RADIUS || y < -PLACE_OUTER_RADIUS ||
                    x > canvas.getWidth() + PLACE_OUTER_RADIUS || y > canvas.getHeight() + PLACE_OUTER_RADIUS) continue;
            graphics.setFill(Color.DARKGOLDENROD);
            graphics.fillOval(x - PLACE_RADIUS, y - PLACE_RADIUS, 2 * PLACE_RADIUS, 2 * PLACE_RADIUS);
            graphics.setStroke(Color.WHITE);
            graphics.setLineWidth(PLACE_STROKE_WIDTH);
            graphics.strokeOval(x - PLACE_RADIUS, y - PLACE_RADIUS, 2 * PLACE_RADIUS, 2 * PLACE_RADIUS);
        }
    }

    double placeScreenX(Position place) {
        return state.screenXFor(place.x() + 0.5, canvas.getWidth());
    }

    double placeScreenY(Position place) {
        return state.screenYFor(place.z() + 0.5, canvas.getHeight());
    }

    PlaceHit placeAt(double x, double y, double snapRadius) {
        return state == null ? null : placeAt(x, y, snapRadius, visibleMarkers());
    }

    PlaceHit placeAt(double x, double y, double snapRadius, List<DisplayMapMarker> visible) {
        PlaceHit closest = null;
        double best = Double.POSITIVE_INFINITY;
        double originalRadius = Math.max(MARKER_RADIUS + 1, snapRadius);
        for (var marker : visible) {
            Position place = marker.position();
            double distance = Math.hypot(x - placeScreenX(place), y - placeScreenY(place));
            if (distance <= originalRadius && distance <= best) {
                closest = new PlaceHit(place, marker);
                best = distance;
            }
        }
        double namedRadius = Math.max(PLACE_OUTER_RADIUS, snapRadius);
        for (Position place : namedPlaces.keySet()) {
            double dx = x - placeScreenX(place);
            double dy = y - placeScreenY(place);
            double distance = Math.hypot(dx, dy);
            if (distance <= namedRadius && distance <= best
                    && (closest == null || !closest.position().equals(place))) {
                best = distance;
                closest = new PlaceHit(place, null);
            }
        }
        return closest;
    }

    private void drawViewerSelections(GraphicsContext graphics) {
        if (!viewerSelectionMode || state == null) {
            return;
        }
        ChunkRectangle active = viewerSelection.active();
        for (ChunkRectangle cached : viewerCachedAreas) {
            if (cached.equals(active)) continue;
            drawViewerRectangle(graphics, cached, Color.rgb(68, 199, 103, 0.08),
                    Color.rgb(68, 199, 103, 0.75), 2);
        }
        if (active != null) {
            boolean locked = !viewerSelection.canSelect();
            boolean loaded = viewerSelection.loaded();
            drawViewerRectangle(
                    graphics,
                    active,
                    !viewerProfile.allows(active) ? Color.rgb(220, 65, 65, 0.16)
                            : loaded ? Color.rgb(68, 199, 103, 0.16)
                            : locked ? Color.rgb(47, 128, 237, 0.22) : Color.rgb(47, 128, 237, 0.13),
                    !viewerProfile.allows(active) ? Color.rgb(220, 65, 65)
                            : loaded ? Color.rgb(68, 199, 103)
                            : locked ? Color.rgb(47, 128, 237, 1.0) : Color.rgb(105, 171, 255, 0.9),
                    locked ? 3 : 2);
        }
    }

    private void drawViewerRectangle(
            GraphicsContext graphics,
            ChunkRectangle rectangle,
            Color fill,
            Color stroke,
            double lineWidth) {
        double worldX = Math.multiplyExact((long) rectangle.minX(), 16L);
        double worldZ = Math.multiplyExact((long) rectangle.minZ(), 16L);
        double blockWidth = Math.multiplyExact((long) rectangle.width(), 16L);
        double blockDepth = Math.multiplyExact((long) rectangle.depth(), 16L);
        double x = state.screenXFor(worldX, canvas.getWidth());
        double y = state.screenYFor(worldZ, canvas.getHeight());
        double width = blockWidth / state.visualBlocksPerPixel();
        double height = blockDepth / state.visualBlocksPerPixel();
        graphics.setFill(fill);
        graphics.fillRect(x, y, width, height);
        graphics.setStroke(stroke);
        graphics.setLineWidth(lineWidth);
        graphics.strokeRect(x, y, width, height);
    }

    private void updateViewerCandidateFromPointer() {
        if (pointerInside) {
            updateViewerCandidate(lastPointerX, lastPointerY);
        } else if (viewerSelection.canSelect()) {
            viewerSelection.clearCandidate();
        }
    }

    private void updateViewerCandidate(double screenX, double screenY) {
        if (!viewerSelectionMode || state == null || !viewerSelection.canSelect()) {
            return;
        }
        if (viewerFreeSelection) {
            if (viewerDragStart == null) {
                return;
            }
            ChunkRectangle end = viewerSelectionAt(state, screenX, screenY,
                    canvas.getWidth(), canvas.getHeight(), ViewerRangePreset.ONE);
            viewerSelection.preview(rectangleBetween(viewerDragStart, end));
            viewerSelectionChanged.accept(viewerSelection.candidate());
            return;
        }
        viewerSelection.preview(viewerSelectionAt(
                state,
                screenX,
                screenY,
                canvas.getWidth(),
                canvas.getHeight(),
                viewerRangePreset));
    }

    static ChunkRectangle rectangleBetween(ChunkRectangle start, ChunkRectangle end) {
        return new ChunkRectangle(Math.min(start.minX(), end.minX()),
                Math.min(start.minZ(), end.minZ()), Math.max(start.maxX(), end.maxX()),
                Math.max(start.maxZ(), end.maxZ()));
    }

    static ChunkRectangle viewerSelectionAt(
            MapViewportState state,
            double screenX,
            double screenY,
            double viewportWidth,
            double viewportHeight,
            ViewerRangePreset preset) {
        if (state == null || preset == null || viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("viewport state, preset and positive size are required");
        }
        return preset.atWorld(
                state.worldXAt(screenX, viewportWidth),
                state.worldZAt(screenY, viewportHeight));
    }

    private void drawTiles(GraphicsContext graphics, boolean targetLevel) {
        for (Map.Entry<MapTileKey, Image> entry : tiles.entrySet()) {
            boolean isTarget = state.zoom() == entry.getKey().zoom();
            if (isTarget != targetLevel) {
                continue;
            }
            drawTile(graphics, entry.getKey(), entry.getValue());
        }
    }

    private void drawBackground(GraphicsContext graphics) {
        graphics.setFill(Color.web("#20262b"));
        graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void drawTile(GraphicsContext graphics, MapTileKey key, Image image) {
        MapTileBounds bounds = key.bounds();
        double x = state.screenXFor(bounds.minX(), canvas.getWidth());
        double y = state.screenYFor(bounds.minZ(), canvas.getHeight());
        double left = tileEdge(x);
        double top = tileEdge(y);
        // Adjacent tiles must quantize the same world edge to the same screen pixel.
        double right = tileEdge(state.screenXFor(bounds.maxXExclusive(), canvas.getWidth()));
        double bottom = tileEdge(state.screenYFor(bounds.maxZExclusive(), canvas.getHeight()));
        if (right <= 0 || bottom <= 0 || left >= canvas.getWidth() || top >= canvas.getHeight()) return;
        graphics.drawImage(image, left, top, right - left, bottom - top);
    }

    private static double tileEdge(double projected) {
        // Wheel round trips can put an integral edge a few floating-point steps below the pixel.
        double nearest = Math.rint(projected);
        return Math.abs(projected - nearest) < 1e-7 ? nearest : Math.floor(projected);
    }

    private void drawPartialTiles(GraphicsContext graphics, boolean target) {
        boolean clipped = false;
        for (Map.Entry<MapTileKey, Image> entry : partialTiles.entrySet()) {
            if (targetKeys.contains(entry.getKey()) != target) continue;
            if (!target && !clipped) {
                // Only fallback partials need a mask over the still-missing target tiles.
                graphics.save();
                graphics.beginPath();
                for (var key : targetKeys) if (!tiles.containsKey(key)) {
                    var b = key.bounds();
                    graphics.rect(state.screenXFor(b.minX(), canvas.getWidth()),
                            state.screenYFor(b.minZ(), canvas.getHeight()),
                            b.blockWidth() / state.visualBlocksPerPixel(), b.blockHeight() / state.visualBlocksPerPixel());
                }
                graphics.clip();
                clipped = true;
            }
            drawTile(graphics, entry.getKey(), entry.getValue());
        }
        if (clipped) graphics.restore();
    }

    private void drawFailedTiles(GraphicsContext graphics) {
        for (MapTileKey key : failedKeys) {
            if (incompleteReasons.containsKey(key)) continue; // The partial image marks only its unreadable coverage.
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
        // Keep recently viewed complete images within the existing display budget.
        trimDisplayImages();
        partialTiles.clear();
        partialTileMarkers.clear();
        incompleteReasons.clear();
    }

    private static double clampVisualZoom(double value) {
        return Math.max(MIN_VISUAL_ZOOM, Math.min(MAX_VISUAL_ZOOM, value));
    }

    private void drawMarker(GraphicsContext graphics, DisplayMapMarker displayMarker) {
        MapMarker marker = displayMarker.marker();
        double x = placeScreenX(displayMarker.position());
        double y = placeScreenY(displayMarker.position());
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
        if (!showPortals.get() || (tileMarkers.isEmpty() && partialTileMarkers.isEmpty())) {
            for (var marker : fixedMarkers) {
                if (!isMarkerVisible(marker)) return fixedMarkers.stream().filter(this::isMarkerVisible).toList();
            }
            return fixedMarkers;
        }
        List<DisplayMapMarker> markers = new ArrayList<>(fixedMarkers);
        List<MapMarker> portals = java.util.stream.Stream.concat(
                        tileMarkers.entrySet().stream().filter(e -> validatedKeys.contains(e.getKey())).map(Map.Entry::getValue),
                        partialTileMarkers.entrySet().stream().filter(e -> targetKeys.contains(e.getKey())).map(Map.Entry::getValue))
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

    void hideMarkerTooltip() {
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
