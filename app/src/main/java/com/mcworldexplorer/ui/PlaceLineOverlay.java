package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.SavedPlaces.LineData;
import com.mcworldexplorer.storage.SavedPlaces.Link;
import com.mcworldexplorer.storage.SavedPlaces.Position;
import com.mcworldexplorer.ui.MapViewport.PlaceHit;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Window;

import java.awt.geom.Line2D;
import java.util.Map;
import java.util.function.BiConsumer;

/** The canvas keeps the primary drag while wheel and secondary pan adjust the camera. */
final class PlaceLineOverlay {
    private static final double EDGE_WIDTH = 40;
    private static final double MAX_PAN_SPEED = 520;
    private static final double SNAP_RADIUS = 9;
    private final MapViewport map;
    private final Canvas canvas;
    final Text measurement = new Text();
    private final AnimationTimer timer = new AnimationTimer() {
        private long previous;
        @Override public void start() { previous = 0; super.start(); }
        @Override public void handle(long now) {
            if (previous != 0 && !panning) {
                double step = MAX_PAN_SPEED * Math.min(.05, (now - previous) / 1e9);
                double dx = edgeSpeed(x, canvas.getWidth()) * step;
                double dy = edgeSpeed(y, canvas.getHeight()) * step;
                if (dx != 0 || dy != 0) map.panLineView(dx, dy);
            }
            previous = now;
        }
    };
    private static double edgeSpeed(double pos, double size) {
        double band = Math.min(EDGE_WIDTH, size / 3);
        if (band <= 0) return 0;
        return Math.max(0, 1 - pos / band) - Math.max(0, 1 - (size - pos) / band);
    }

    private Map<Link, LineData> lines = Map.of();
    private Link hovered;
    private PlaceHit start;
    private double pressX, pressY, x, y;
    private boolean dragging, panning;
    private Scene scene;
    private Window window;
    private Robot pointer;
    private BiConsumer<PlaceHit, PlaceHit> complete = (a, b) -> {};
    private final ChangeListener<Boolean> focus = (observable, oldValue, focused) -> { if (!focused) cancel(); };
    private final EventHandler<KeyEvent> escape = event -> {
        if (event.getCode() == KeyCode.ESCAPE) { cancel(); event.consume(); }
    };
    private final EventHandler<ScrollEvent> wheel = this::scroll;

    PlaceLineOverlay(MapViewport map, Canvas canvas) {
        this.map = map;
        this.canvas = canvas;
        measurement.setId("lineMeasurement");
        measurement.setManaged(false);
        measurement.setMouseTransparent(true);
        measurement.setVisible(false);
        measurement.setFont(Font.font(11));
        measurement.setFill(Color.WHITE);
        measurement.setStroke(Color.rgb(0, 0, 0, .75));
        measurement.setStrokeType(StrokeType.OUTSIDE);
        measurement.setStrokeWidth(1);
        canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, this::press);
        canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::drag);
        canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, this::release);
        canvas.sceneProperty().addListener((observable, oldScene, newScene) -> cancel());
    }

    void setOnComplete(BiConsumer<PlaceHit, PlaceHit> handler) { complete = handler; }

    boolean isDragging() { return dragging; }

    void setLines(Map<Link, LineData> current) {
        if (lines.equals(current)) return;
        lines = Map.copyOf(current);
        hovered = null;
        map.draw();
    }

    Link hit(double px, double py) {
        Link closest = null;
        double best = 36; // Six logical pixels at every zoom.
        for (Link link : lines.keySet()) {
            double d = Line2D.ptSegDistSq(map.placeScreenX(link.a()), map.placeScreenY(link.a()),
                    map.placeScreenX(link.b()), map.placeScreenY(link.b()), px, py);
            if (d <= best) { closest = link; best = d; }
        }
        return closest;
    }

    void hover(Link line) {
        if (!java.util.Objects.equals(hovered, line)) { hovered = line; map.draw(); }
    }

    private void press(MouseEvent event) {
        if (start != null && event.getButton() == MouseButton.SECONDARY) {
            panning = true;
            movePointer(event.getX(), event.getY());
            canvas.setCursor(Cursor.CLOSED_HAND);
            event.consume();
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY || event.isSecondaryButtonDown()
                || map.isViewerSelectionMode() || map.viewportState() == null) return;
        start = map.placeAt(event.getX(), event.getY(), 0);
        if (start == null) return;
        map.requestFocus();
        map.hideMarkerTooltip();
        hover(null);
        pressX = x = event.getX();
        pressY = y = event.getY();
        scene = canvas.getScene();
        if (scene != null) {
            scene.addEventFilter(ScrollEvent.SCROLL, wheel);
            scene.addEventFilter(KeyEvent.KEY_PRESSED, escape);
            window = scene.getWindow();
            if (window != null) {
                window.focusedProperty().addListener(focus);
                window.showingProperty().addListener(focus);
            }
        }
        event.consume();
    }

    private void scroll(ScrollEvent event) {
        var at = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
        movePointer(at.getX(), at.getY());
        map.wheelZoom(x, y, event.getDeltaY());
        event.consume();
    }

    private void drag(MouseEvent event) {
        if (start == null) return;
        double oldX = x, oldY = y;
        movePointer(event.getX(), event.getY());
        if (!dragging && (Math.hypot(x - pressX, y - pressY) > MapViewport.CLICK_DRAG_THRESHOLD || panning)) {
            dragging = true;
            timer.start();
        }
        if (dragging) {
            if (panning) map.panLineView(x - oldX, y - oldY);
            else { canvas.setCursor(Cursor.CROSSHAIR); map.draw(); }
        }
        event.consume();
    }

    private void movePointer(double localX, double localY) {
        x = Math.clamp(localX, 0, Math.max(0, canvas.getWidth() - 1));
        y = Math.clamp(localY, 0, Math.max(0, canvas.getHeight() - 1));
        if ((localX < 0 || localX >= canvas.getWidth() || localY < 0 || localY >= canvas.getHeight())
                && window != null && window.isFocused()) {
            // Warp just inside the canvas so fractional display scaling cannot round back outside.
            if (canvas.getWidth() >= 3) x = Math.clamp(x, 1, canvas.getWidth() - 2);
            if (canvas.getHeight() >= 3) y = Math.clamp(y, 1, canvas.getHeight() - 2);
            var screen = canvas.localToScreen(x, y);
            if (screen != null) {
                if (pointer == null) pointer = new Robot();
                pointer.mouseMove(screen);
            }
        }
        map.trackPointer(x, y);
    }

    private PlaceHit target() {
        PlaceHit snap = map.placeAt(x, y, SNAP_RADIUS);
        if (snap != null) return snap;
        long bx = map.blockXAt(x), bz = map.blockZAt(y);
        if (bx != (int) bx || bz != (int) bz) return null;
        return new PlaceHit(new Position(start.position().dimension(), (int) bx, (int) bz), null);
    }

    private void release(MouseEvent event) {
        if (start == null) return;
        event.consume();
        if (event.getButton() == MouseButton.SECONDARY) {
            panning = false;
            canvas.setCursor(dragging ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        } else if (event.getButton() == MouseButton.PRIMARY) {
            movePointer(event.getX(), event.getY());
            PlaceHit from = start;
            PlaceHit to = dragging ? target() : null;
            boolean click = !dragging && Math.hypot(x - pressX, y - pressY) <= MapViewport.CLICK_DRAG_THRESHOLD;
            cancel(); // Release capture before showing a naming dialog.
            if (to != null && !from.position().equals(to.position())) complete.accept(from, to);
            else if (click && from.original() != null) map.centerOn(from.position().x(), from.position().z());
        }
    }

    void cancel() {
        if (start == null) return;
        timer.stop();
        if (window != null) {
            window.focusedProperty().removeListener(focus);
            window.showingProperty().removeListener(focus);
        }
        if (scene != null) {
            scene.removeEventFilter(ScrollEvent.SCROLL, wheel);
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
        }
        window = null;
        scene = null;
        start = null;
        dragging = panning = false;
        measurement.setVisible(false);
        canvas.setCursor(Cursor.DEFAULT);
        map.draw();
    }

    void draw(GraphicsContext g, java.util.List<DisplayMapMarker> visible) {
        g.save();
        lines.forEach((link, style) -> {
            g.setStroke(Color.web(style.color()));
            g.setLineWidth(link.equals(hovered) ? 3.5 : 2);
            g.strokeLine(map.placeScreenX(link.a()), map.placeScreenY(link.a()),
                    map.placeScreenX(link.b()), map.placeScreenY(link.b()));
        });
        if (dragging) {
            PlaceHit snap = map.placeAt(x, y, SNAP_RADIUS, visible);
            double endX = snap == null ? x : map.placeScreenX(snap.position());
            double endY = snap == null ? y : map.placeScreenY(snap.position());
            g.setStroke(Color.web(LineData.DEFAULT.color()));
            g.setLineWidth(2);
            g.setLineDashes(6, 4);
            double startX = map.placeScreenX(start.position()), startY = map.placeScreenY(start.position());
            g.strokeLine(startX, startY, endX, endY);
            long blockX = snap == null ? map.blockXAt(x) : snap.position().x();
            long blockZ = snap == null ? map.blockZAt(y) : snap.position().z();
            measurement.setText("X " + Math.abs(blockX - start.position().x()) + "  ·  Z "
                    + Math.abs(blockZ - start.position().z()) + " 格");
            positionMeasurement(startX, startY, endX, endY);
            if (snap != null) {
                g.setLineDashes();
                g.strokeOval(endX - 7, endY - 7, 14, 14);
            }
        }
        g.restore();
    }

    private void positionMeasurement(double startX, double startY, double endX, double endY) {
        double dx = startX - endX, dy = startY - endY;
        double width = canvas.getWidth(), height = canvas.getHeight();
        // Edge panning can move the first icon off screen. Use the midpoint of the visible segment.
        double fraction = Math.clamp(Math.min(distanceToEdge(endX, dx, width), distanceToEdge(endY, dy, height)), 0, 1);
        double midX = endX + dx * fraction / 2, midY = endY + dy * fraction / 2;
        var bounds = measurement.getLayoutBounds();
        double w = bounds.getWidth(), h = bounds.getHeight();
        measurement.setVisible(w + 4 <= width && h + 4 <= height);
        if (!measurement.isVisible()) return;
        // Keep the text horizontal. Above a steep line, place it on the open side of the midpoint.
        double left = midX - w / 2;
        double top = midY - h - 4;
        if (Math.abs(dy) > Math.abs(dx)) {
            left = dx * dy < 0 ? midX - w - 4 : midX + 4;
        } else if (dx != 0) {
            top -= Math.abs(dy / dx) * w / 2;
        }
        measurement.relocate(Math.clamp(left, 2, width - w - 2), Math.clamp(top, 2, height - h - 2));
    }

    private static double distanceToEdge(double end, double delta, double size) {
        if (delta > 0) return (size - end) / delta;
        if (delta < 0) return -end / delta;
        return 1;
    }
}
