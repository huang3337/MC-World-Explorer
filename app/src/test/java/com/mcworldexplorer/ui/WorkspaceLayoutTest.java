package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.storage.SavedPlaces;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.world.WorldInfo;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.PopupWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses isolated configuration; real-save access is opt-in through MCWORLD_UI_TEST_WORLD. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="mcwe.ui.tests", matches="true")
class WorkspaceLayoutTest {
    @TempDir
    Path home;

    @BeforeAll
    static void startToolkit() throws Exception {
        // Initialize process-wide logging before any temporary application-home override.
        org.slf4j.LoggerFactory.getILoggerFactory();
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(15, TimeUnit.SECONDS), "JavaFX toolkit startup timed out");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rightClickPlacesKeepSelectionAndSurviveReopening() throws Exception {
        Path world = home.resolve("empty-test-world");
        var position = new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, -29, 55);
        var nether = new SavedPlaces.Position(WorldDimension.NETHER_ID, -29, 55);
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Stage owner = new Stage();
            owner.setScene(root.getScene());
            owner.setOpacity(0);
            owner.show();
            try {
                MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                var canvas = viewport.lookup("Canvas");
                var initial = viewport.viewportState();
                double beforeDrag = initial.centerX();
                drag(canvas, 96, 80, 120, 80, MouseButton.SECONDARY);
                assertEquals(beforeDrag - 24 * initial.visualBlocksPerPixel(), initial.centerX());
                assertTrue(Window.getWindows().stream().noneMatch(ContextMenu.class::isInstance));
                ((ToggleButton) root.lookup("#viewerModeButton")).fire();
                var ranges = (ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption>) root.lookup("#viewerRangeComboBox");
                ranges.setValue(com.mcworldexplorer.viewer.ViewerRangeOption.FREE);
                root.applyCss();
                root.layout();
                var view = viewport.viewportState();
                view.zoomVisualAt(1.5, 0, 0, viewport.getWidth(), viewport.getHeight());
                view.centerOn(-25, 50);
                drag(canvas, 90, 65, 106, 77, MouseButton.PRIMARY);
                var locked = viewport.viewerLockedSelection().orElseThrow();

                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, 85, 70, MouseButton.SECONDARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, 105, 82, MouseButton.SECONDARY));
                assertEquals(-55, view.centerX());
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, 85, 70, MouseButton.SECONDARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, 85, 70, MouseButton.SECONDARY));
                assertEquals(-25, view.centerX());
                assertTrue(Window.getWindows().stream().noneMatch(ContextMenu.class::isInstance));
                double x = view.screenXFor(-28.75, viewport.getWidth());
                double y = view.screenYFor(55.25, viewport.getHeight());
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, x, y, MouseButton.SECONDARY));
                // A small release jitter crosses a block boundary but does not change the pressed block.
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, x + 1, y, MouseButton.SECONDARY));
                var menu = showingPlaceMenu();
                assertTrue(menu.getItems().getFirst().getText().contains("X -29 · Z 55"));
                assertTrue(menu.getItems().stream().anyMatch(i -> i.getText() != null && i.getText().equals("复制坐标")));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, 18, 22, MouseButton.PRIMARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, 18, 22, MouseButton.PRIMARY));
                assertFalse(menu.isShowing(), "左键点地图应关闭地点菜单");
                assertEquals(locked, viewport.viewerLockedSelection().orElseThrow());
                drag(canvas, x, y, x, y, MouseButton.SECONDARY);
                runPlaceDialog(showingPlaceMenu(), "标记此处…", "基地", ButtonType.OK);
                assertEquals("基地", new SavedPlaces(world).read().get(position));

                double edgeX = view.screenXFor(-28.5, viewport.getWidth()) + 4.5;
                double edgeY = view.screenYFor(55.5, viewport.getHeight());
                drag(canvas, edgeX, edgeY, edgeX, edgeY, MouseButton.SECONDARY);
                runPlaceDialog(showingPlaceMenu(), "重命名…", "主基地", ButtonType.OK);
                drag(canvas, x, y, x, y, MouseButton.SECONDARY);
                runPlaceDialog(showingPlaceMenu(), "删除标记…", null, ButtonType.CANCEL);
                assertEquals("主基地", new SavedPlaces(world).read().get(position));
                Menu places = myPlaces(root);
                places.getItems().getFirst().fire();
                assertEquals(-28.5, view.centerX());
                assertEquals(55.5, view.centerZ());
                assertEquals(1.5, view.visualBlocksPerPixel());
                assertEquals(locked, viewport.viewerLockedSelection().orElseThrow());
                new SavedPlaces(world).change(nether, null, "下界门");
            } finally {
                owner.close();
            }
        });

        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Menu places = myPlaces(root);
            var destination = places.getItems().stream().filter(i -> i.getText().startsWith("下界门")).findFirst().orElseThrow();
            var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            ((ToggleButton) root.lookup("#viewerModeButton")).fire();
            var canvasBeforeSwitch = viewport.lookup("Canvas");
            Event.fireEvent(canvasBeforeSwitch, pointer(canvasBeforeSwitch, MouseEvent.MOUSE_MOVED, 110, 85, MouseButton.NONE));
            Event.fireEvent(canvasBeforeSwitch, pointer(canvasBeforeSwitch, MouseEvent.MOUSE_PRESSED, 110, 85, MouseButton.PRIMARY));
            Event.fireEvent(canvasBeforeSwitch, pointer(canvasBeforeSwitch, MouseEvent.MOUSE_RELEASED, 110, 85, MouseButton.PRIMARY));
            assertTrue(viewport.viewerLockedSelection().isPresent());
            destination.fire();
            awaitFx(() -> viewport.viewportState() != null && viewport.viewportState().centerX() == -28.5);
            var dimensions = (ComboBox<WorldDimension>) root.lookup("#dimensionComboBox");
            assertEquals(WorldDimension.NETHER_ID, dimensions.getValue().id());
            assertEquals(55.5, viewport.viewportState().centerZ());
            assertTrue(viewport.viewerLockedSelection().isEmpty());
            Stage owner = new Stage();
            owner.setOpacity(0);
            owner.setScene(root.getScene());
            owner.show();
            try {
                root.layout();
                var canvas = viewport.lookup("Canvas");
                double edgeX = viewport.getWidth()/2 - 4.5;
                double edgeY = viewport.getHeight()/2;
                drag(canvas, edgeX, edgeY, edgeX, edgeY, MouseButton.SECONDARY);
                runPlaceDialog(showingPlaceMenu(), "删除标记…", null, ButtonType.OK);
                assertEquals(Map.of(position, "主基地"), new SavedPlaces(world).read());
            } finally {
                owner.close();
            }
        });
    }

    @Test
    void placeMenuHitAreaFollowsTheVisibleCircleAtEachZoom() throws Exception {
        withWorkspace(root -> {
            var world = Files.createDirectories(home.resolve("empty-test-world"));
            var store = new SavedPlaces(world);
            store.change(new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, -11, -6), null, "西");
            store.change(new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, -8, -6), null, "东");
            loadEmptyTestWorld(root);
            Stage stage = new Stage();
            stage.setScene(root.getScene());
            stage.setOpacity(0);
            try {
                stage.show();
                root.layout();
                MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                var view = viewport.viewportState();
                for (double scale : new double[]{2, 0.25}) {
                    view.zoomVisualAt(scale, 0, 0, viewport.getWidth(), viewport.getHeight());
                    viewport.centerOn(-10.5, -5.5);
                    double x = view.screenXFor(-10.5, viewport.getWidth());
                    double y = view.screenYFor(-5.5, viewport.getHeight());
                    assertHitMenu(viewport, x - 4.7, y, "西 ·", "重命名…");
                    assertHitMenu(viewport, x - 4.9, y, "主世界", "标记此处…");
                    assertHitMenu(viewport, x - 4, y + 4, "主世界", "标记此处…");
                    if (scale == 2) {
                        assertHitMenu(viewport, x + 0.25, y + 3, "西 ·", "重命名…");
                        assertHitMenu(viewport, x + 1.25, y + 3, "东 ·", "重命名…");
                    }
                    // The same nearby world coordinate becomes available for a new place when zoomed in.
                    assertHitMenu(viewport, view.screenXFor(-12.5, viewport.getWidth()), y,
                            scale == 2 ? "西 ·" : "主世界 · X -13", scale == 2 ? "重命名…" : "标记此处…");
                }
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void connectsPlacesAfterWheelEdgePanAndSecondaryDrag() throws Exception {
        withWorkspace(root -> {
            var world = Files.createDirectories(home.resolve("empty-test-world"));
            var store = new SavedPlaces(world);
            var a = new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, 10, 20);
            var b = new SavedPlaces.Position(a.dimension(), 40, 60);
            store.change(a, null, "基地");
            store.change(b, null, "村庄");
            loadEmptyTestWorld(root);
            var stage = new Stage();
            stage.setScene(root.getScene());
            stage.setOpacity(0);
            try {
                stage.show();
                root.layout();
                MapViewport map = (MapViewport) root.lookup("#viewportHost").lookup("MapViewport");
                var canvas = map.lookup("Canvas");
                var view = map.viewportState();
                view.zoomVisualAt(1, 0, 0, map.getWidth(), map.getHeight());
                map.centerOn(10.5, 20.5);
                double startX = map.placeScreenX(a), startY = map.placeScreenY(a);
                var measurement = (javafx.scene.text.Text) map.lookup("#lineMeasurement");
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, startX, startY, MouseButton.PRIMARY));
                assertFalse(measurement.isVisible());
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, startX - 62, startY - 25, MouseButton.PRIMARY));
                assertTrue(measurement.isVisible());
                assertTrue(measurement.isMouseTransparent());
                assertEquals("X 62  ·  Z 25 格", measurement.getText());
                assertTrue(measurement.getBoundsInParent().getMaxY() < startY - 12.5);
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, startX + 12, startY, MouseButton.PRIMARY));
                var wheel = new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                        startX, startY, startX, startY, false, false, false, false, false, false, 0, 40, 0, 40,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0,
                        new javafx.scene.input.PickResult(canvas, new javafx.geometry.Point3D(startX, startY, 0), 0));
                Event.fireEvent(canvas, wheel);
                assertTrue(view.visualBlocksPerPixel() < 1);
                double oldCenter = view.centerX();
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, map.getWidth() - 2, startY, MouseButton.PRIMARY));
                awaitFx(() -> view.centerX() > oldCenter + 4);
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, startX, startY, MouseButton.PRIMARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, startX, startY, MouseButton.SECONDARY, true));
                double beforePan = view.centerX();
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, startX + 16, startY, MouseButton.SECONDARY, true));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, startX + 16, startY, MouseButton.SECONDARY, true));
                assertEquals(beforePan - 16 * view.visualBlocksPerPixel(), view.centerX(), .001);
                assertTrue(map.placeLines.isDragging());
                assertTrue(Window.getWindows().stream().noneMatch(ContextMenu.class::isInstance));
                double endX = map.placeScreenX(b) + 5, endY = map.placeScreenY(b);
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, endX, endY, MouseButton.PRIMARY));
                assertEquals("X 30  ·  Z 40 格", measurement.getText());
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, endX, endY, MouseButton.PRIMARY));
                assertFalse(measurement.isVisible());
                var link = new SavedPlaces.Link(a, b);
                assertEquals(Map.of(link, SavedPlaces.LineData.DEFAULT), store.snapshot().lines());
                assertFalse(map.placeLines.isDragging());
                double midX = (map.placeScreenX(a) + map.placeScreenX(b)) / 2;
                double midY = (map.placeScreenY(a) + map.placeScreenY(b)) / 2;
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_MOVED, midX, midY, MouseButton.NONE));
                var tip = Window.getWindows().stream().filter(javafx.scene.control.Tooltip.class::isInstance)
                        .map(javafx.scene.control.Tooltip.class::cast).filter(Window::isShowing).findFirst().orElseThrow();
                assertEquals("X 轴相距 30 格\nZ 轴相距 40 格", tip.getText());
                drag(canvas, midX, midY, midX, midY, MouseButton.SECONDARY);
                runPlaceDialog(showingPlaceMenu(), "编辑备注…", "铁路", ButtonType.OK);
                assertEquals("铁路", store.snapshot().lines().get(link).note());
                drag(canvas, midX, midY, midX, midY, MouseButton.SECONDARY);
                var colors = showingPlaceMenu();
                colors.hide();
                Platform.runLater(() -> {
                    var pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                            .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                    ((javafx.scene.control.ColorPicker) pane.lookup(".color-picker")).setValue(javafx.scene.paint.Color.RED);
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                });
                colors.getItems().stream().filter(item -> "设置颜色…".equals(item.getText())).findFirst().orElseThrow().fire();
                assertEquals(new SavedPlaces.LineData("#FF0000", "铁路"), store.snapshot().lines().get(link));
                drag(canvas, midX, midY, midX, midY, MouseButton.SECONDARY);
                var menu = showingPlaceMenu();
                menu.hide();
                menu.getItems().stream().filter(item -> "删除连线".equals(item.getText())).findFirst().orElseThrow().fire();
                assertTrue(store.snapshot().lines().isEmpty());
                assertEquals(2, store.read().size());
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, map.placeScreenX(a), map.placeScreenY(a), MouseButton.PRIMARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, midX, midY, MouseButton.PRIMARY));
                Event.fireEvent(canvas, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertFalse(measurement.isVisible());
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, endX, endY, MouseButton.PRIMARY));
                assertFalse(map.placeLines.isDragging());
                assertTrue(store.snapshot().lines().isEmpty());
            } finally { stage.close(); }
        });
    }

    @Test
    void emptyLineEndpointRequiresAConfirmedName() throws Exception {
        withWorkspace(root -> {
            var store = new SavedPlaces(Files.createDirectories(home.resolve("empty-test-world")));
            var start = new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, -30, -40);
            store.change(start, null, "旧地点");
            loadEmptyTestWorld(root);
            var stage = new Stage();
            stage.setScene(root.getScene());
            stage.setOpacity(0);
            try {
                stage.show();
                root.layout();
                var map = (MapViewport) root.lookup("#viewportHost").lookup("MapViewport");
                var canvas = map.lookup("Canvas");
                map.centerOn(-29.5, -39.5);
                double x = map.placeScreenX(start), y = map.placeScreenY(start);
                for (ButtonType answer : List.of(ButtonType.CANCEL, ButtonType.OK)) {
                    Platform.runLater(() -> {
                        var pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                        ((TextField) pane.lookup(".text-field")).setText("新终点");
                        ((Button) pane.lookupButton(answer)).fire();
                    });
                    drag(canvas, x, y, x + 50, y - 30, MouseButton.PRIMARY);
                    var snapshot = store.snapshot();
                    assertEquals(answer == ButtonType.OK ? 2 : 1, snapshot.places().size());
                    assertEquals(answer == ButtonType.OK ? 1 : 0, snapshot.lines().size());
                }
            } finally { stage.close(); }
        });
    }

    @Test
    void builtinIconsCanStartAndFinishLinesWithoutBeingSavedFirst() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            var store = new SavedPlaces(home.resolve("empty-test-world"));
            var stage = new Stage();
            stage.setScene(root.getScene());
            stage.setOpacity(0);
            try {
                stage.show();
                root.layout();
                var map = (MapViewport) root.lookup("#viewportHost").lookup("MapViewport");
                var canvas = map.lookup("Canvas");
                String dim = WorldDimension.OVERWORLD_ID;
                var spawn = DisplayMapMarker.standard(com.mcworldexplorer.map.MapMarker.point(
                        com.mcworldexplorer.map.MapMarkerType.WORLD_SPAWN, dim, -61, 68, 8));
                var player = DisplayMapMarker.player(com.mcworldexplorer.map.MapMarker.point(
                        com.mcworldexplorer.map.MapMarkerType.PLAYER, dim, 20, 79, -33),
                        "p1", "小明", "主世界", 20.7, 79.1, -32.4);
                var portal = com.mcworldexplorer.map.MapMarker.portal(dim, 71, 68, 5, 75, 72, 5);
                var gate = DisplayMapMarker.standard(portal).position();
                map.setFixedMarkers(List.of(spawn, player));
                var view = map.viewportState();
                view.zoomVisualAt(1, 0, 0, map.getWidth(), map.getHeight());
                map.centerOn(0, 0);
                double sx = map.placeScreenX(spawn.position()), sy = map.placeScreenY(spawn.position());
                double px = map.placeScreenX(player.position()), py = map.placeScreenY(player.position());
                drag(canvas, sx + 7, sy, sx + 7, sy, MouseButton.SECONDARY);
                var menu = showingPlaceMenu();
                assertTrue(menu.getItems().getFirst().getText().startsWith("世界出生点 · 主世界 · X -61 · Z 8"));
                menu.hide();
                menu.getItems().stream().filter(i -> "复制坐标".equals(i.getText())).findFirst().orElseThrow().fire();
                assertEquals("-61, 8", Clipboard.getSystemClipboard().getString());
                assertTrue(store.read().isEmpty());
                map.showSpawnProperty().set(false);
                assertNull(map.placeAt(sx, sy, 0));
                map.showSpawnProperty().set(true);
                drag(canvas, sx - 7, sy, px + 6, py, MouseButton.PRIMARY);
                var link = new SavedPlaces.Link(spawn.position(), player.position());
                assertEquals("世界出生点 — 小明", store.snapshot().lines().get(link).title(link, store.read()));
                assertTrue(store.read().isEmpty());
                var key = new com.mcworldexplorer.map.MapTileKey("test", dim,
                        com.mcworldexplorer.preview.PreviewLayer.surfaceOverview(),
                        com.mcworldexplorer.map.MapZoomLevel.BLOCKS_1, 0, 0, "test");
                map.setTargetTiles(List.of(key));
                map.showTile(key, new WritableImage(256, 256), List.of(portal));
                drag(canvas, map.placeScreenX(gate), map.placeScreenY(gate), sx, sy, MouseButton.PRIMARY);
                assertTrue(store.read().isEmpty());
                assertTrue(store.snapshot().lines().containsKey(new SavedPlaces.Link(gate, spawn.position())));
                assertTrue(myPlaces(root).getItems().getFirst().isDisable());
                assertEquals(0, view.centerX(), .001, "拖动自动图标不能先跳到图标中心");
                drag(canvas, sx, sy, sx, sy, MouseButton.PRIMARY);
                assertEquals(-61, view.centerX());
                sx = map.placeScreenX(spawn.position()); sy = map.placeScreenY(spawn.position());
                drag(canvas, sx, sy, sx, sy, MouseButton.SECONDARY);
                var spawnMenu = showingPlaceMenu();
                assertTrue(spawnMenu.getItems().stream().noneMatch(item -> item.getText() != null && item.getText().contains("删除")));
                runPlaceDialog(spawnMenu, "在此创建自定义标记…", "世界起点", ButtonType.OK);
                assertEquals("世界起点", store.read().get(spawn.position()));
                drag(canvas, sx, sy, sx, sy, MouseButton.SECONDARY);
                var overlap = showingPlaceMenu();
                var custom = overlap.getItems().stream().filter(Menu.class::isInstance).map(Menu.class::cast)
                        .filter(item -> item.getText().contains("世界起点")).findFirst().orElseThrow();
                var deleteCustom = custom.getItems().stream().filter(i -> "删除标记…".equals(i.getText())).findFirst().orElseThrow();
                overlap.hide();
                Platform.runLater(() -> {
                    var pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                            .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                });
                deleteCustom.fire();
                assertTrue(store.read().isEmpty());
                assertEquals(2, store.snapshot().lines().size());
                assertHitMenu(map, sx, sy, "世界出生点 ·", "复制坐标");
            } finally { stage.close(); }
        });
    }

    @Test
    void builtinAndCustomEndpointsShareCoordinatesWithoutWritingOnCancelledNaming() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Path world = home.resolve("empty-test-world");
            var originalFiles = snapshotWorld(world);
            var store = new SavedPlaces(world);
            var stage = new Stage();
            stage.setScene(root.getScene());
            stage.setOpacity(0);
            try {
                stage.show();
                root.layout();
                var map = (MapViewport) root.lookup("#viewportHost").lookup("MapViewport");
                var canvas = map.lookup("Canvas");
                String dim = WorldDimension.OVERWORLD_ID;
                var player = DisplayMapMarker.player(com.mcworldexplorer.map.MapMarker.point(
                        com.mcworldexplorer.map.MapMarkerType.PLAYER, dim, -25, 84, 10),
                        "p", "老王", "主世界", -24.9, 84, 10.8);
                map.setFixedMarkers(List.of(player));
                map.viewportState().zoomVisualAt(1, 0, 0, map.getWidth(), map.getHeight());
                map.centerOn(0, 0);
                double x = map.placeScreenX(player.position()), y = map.placeScreenY(player.position());
                var cabin = new SavedPlaces.Position(dim, -75, 28);
                for (var answer : List.of(ButtonType.CANCEL, ButtonType.OK)) {
                    Platform.runLater(() -> {
                        var pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                        ((TextField) pane.lookup(".text-field")).setText("小屋");
                        ((Button) pane.lookupButton(answer)).fire();
                    });
                    drag(canvas, x, y, map.placeScreenX(cabin), map.placeScreenY(cabin), MouseButton.PRIMARY);
                    if (answer == ButtonType.CANCEL) {
                        assertTrue(store.read().isEmpty());
                        assertTrue(store.snapshot().lines().isEmpty());
                    }
                }
                // The measurement keeps the old coordinate without leaving a marker there.
                var moved = DisplayMapMarker.player(com.mcworldexplorer.map.MapMarker.point(
                        com.mcworldexplorer.map.MapMarkerType.PLAYER, dim, 16, 65, 30),
                        "p", "老王", "主世界", 16, 65, 30);
                map.setFixedMarkers(List.of(moved));
                assertNull(map.placeAt(x, y, 0));
                drag(canvas, map.placeScreenX(cabin), map.placeScreenY(cabin),
                        map.placeScreenX(moved.position()), map.placeScreenY(moved.position()), MouseButton.PRIMARY);
                var saved = store.snapshot();
                assertEquals(Map.of(cabin, "小屋"), saved.places());
                assertTrue(saved.lines().containsKey(new SavedPlaces.Link(cabin, player.position())));
                assertTrue(saved.lines().containsKey(new SavedPlaces.Link(cabin, moved.position())));
                drag(canvas, map.placeScreenX(cabin), map.placeScreenY(cabin), map.placeScreenX(cabin), map.placeScreenY(cabin), MouseButton.SECONDARY);
                runPlaceDialog(showingPlaceMenu(), "删除标记…", null, ButtonType.OK);
                assertTrue(store.snapshot().lines().isEmpty());
                assertTrue(store.read().isEmpty());
                assertEquals(moved, map.placeAt(map.placeScreenX(moved.position()), map.placeScreenY(moved.position()), 0).original());
                assertEquals(originalFiles, snapshotWorld(world));
            } finally { stage.close(); }
        });
    }

    private static void assertHitMenu(MapViewport viewport, double x, double y, String heading, String action) {
        drag(viewport.lookup("Canvas"), x, y, x, y, MouseButton.SECONDARY);
        var menu = showingPlaceMenu();
        try {
            assertTrue(menu.getItems().getFirst().getText().startsWith(heading), menu.getItems().getFirst().getText());
            assertTrue(menu.getItems().stream().anyMatch(item -> action.equals(item.getText())));
        } finally {
            menu.hide();
        }
    }

    private static Menu myPlaces(Parent root) {
        return ((MenuButton) root.lookup("#locateMenuButton")).getItems().stream()
                .filter(Menu.class::isInstance).map(Menu.class::cast)
                .filter(item -> "我的地点".equals(item.getText())).findFirst().orElseThrow();
    }

    private static ContextMenu showingPlaceMenu() {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance)
                .map(ContextMenu.class::cast).filter(ContextMenu::isShowing).findFirst().orElseThrow();
    }

    private static void runPlaceDialog(ContextMenu menu, String action, String name, ButtonType answer) {
        MenuItem item = menu.getItems().stream().filter(i -> action.equals(i.getText())).findFirst().orElseThrow();
        menu.hide();
        Platform.runLater(() -> {
            DialogPane pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                    .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
            if (name != null) ((TextField) pane.lookup(".text-field")).setText(name);
            ((Button) pane.lookupButton(answer)).fire();
        });
        item.fire();
    }

    @Test
    @SuppressWarnings("unchecked")
    void libraryControlsPreserveOpenMapAndLockedSelectionAcrossRefresh() throws Exception {
        Path game = home.resolve("game");
        Path saves = game.resolve("saves");
        Path base = saves.resolve("Survival_02");
        Path other = saves.resolve("other");
        writeLibraryWorld(base, "山间基地", 800);
        writeLibraryWorld(other, "阿林", 100);
        writeLibraryWorld(saves.resolve("unknown"), "山间基地", 0);
        writeLibraryWorld(game.resolve("versions/pack/saves/twin"), "山间基地", 900);
        withWorkspace(game, root -> {
            TreeView<WorldTreeNode> tree = (TreeView<WorldTreeNode>) root.lookup("#worldTreeView");
            Button refresh = (Button) root.lookup("#refreshLibraryButton");
            Button choose = (Button) root.lookup("#chooseFolderButton");
            Label status = (Label) root.lookup("#scanStatusLabel");
            Label title = (Label) root.lookup("#worldNameLabel");
            TextField search = (TextField) root.lookup("#worldSearch");
            MenuButton sort = (MenuButton) root.lookup("#worldSortMenu");
            MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            awaitFx(() -> !refresh.isDisabled());
            var group = tree.getRoot().getChildren().getFirst();
            assertEquals(2, tree.getRoot().getChildren().size());
            assertEquals(List.of("Survival_02", "other", "unknown"), libraryFolders(group));
            tree.getSelectionModel().select(group.getChildren().getFirst());
            assertEquals("山间基地", title.getText());
            ToggleButton selectionMode = (ToggleButton) root.lookup("#viewerModeButton");
            awaitFx(() -> viewport.viewportState() != null && !selectionMode.isDisabled());
            selectionMode.fire();
            var ranges = (ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption>) root.lookup("#viewerRangeComboBox");
            ranges.setValue(com.mcworldexplorer.viewer.ViewerRangeOption.FREE);
            root.applyCss();
            root.layout();
            var view = viewport.viewportState();
            view.zoomVisualAt(1, 0, 0, viewport.getWidth(), viewport.getHeight());
            view.centerOn(-125, 318);
            drag(viewport.lookup("Canvas"), 100, 80, 124, 96, MouseButton.PRIMARY);
            var locked = viewport.viewerLockedSelection().orElseThrow();
            Runnable checkView = () -> {
                assertSame(view, viewport.viewportState());
                assertEquals(-125, view.centerX());
                assertEquals(318, view.centerZ());
                assertEquals(1, view.visualBlocksPerPixel());
                assertEquals(locked, viewport.viewerLockedSelection().orElseThrow());
                assertFalse(((Button) root.lookup("#viewerConfirmButton")).isDisabled());
            };

            search.setText("无匹配");
            assertTrue(tree.getRoot().getChildren().isEmpty());
            assertTrue(status.getText().contains("0 / 4"));
            assertNull(tree.getSelectionModel().getSelectedItem());
            checkView.run();
            search.setText("  sUrViVaL  ");
            assertEquals(1, tree.getRoot().getChildren().size());
            assertEquals(base, tree.getSelectionModel().getSelectedItem().getValue().getWorldInfo().getFolderPath());
            search.setText("基地");
            assertTrue(status.getText().contains("3 / 4"));
            search.clear();
            ((RadioMenuItem) sort.getItems().get(1)).setSelected(true);
            assertEquals(List.of("other", "Survival_02", "unknown"), libraryFolders(tree.getRoot().getChildren().getFirst()));
            checkView.run();

            writeLibraryWorld(base, "更新的基地", 1000);
            refresh.fire();
            assertTrue(refresh.isDisabled());
            assertTrue(choose.isDisabled());
            search.setText("  OTHER "); // Filter can change while the old list is visible.
            awaitFx(() -> !refresh.isDisabled());
            assertEquals("更新的基地", title.getText());
            assertNull(tree.getSelectionModel().getSelectedItem());
            assertTrue(status.getText().contains("1 / 4"));
            checkView.run();
            search.clear();
            assertEquals(base, tree.getSelectionModel().getSelectedItem().getValue().getWorldInfo().getFolderPath());

            Files.writeString(base.resolve("level.dat"), "invalid NBT");
            writeLibraryWorld(saves.resolve("new"), "新世界", 500);
            refresh.fire();
            awaitFx(() -> !refresh.isDisabled());
            assertTrue(status.getText().contains("保留 1 个世界"));
            assertTrue(libraryFolders(tree.getRoot().getChildren().getFirst()).contains("new"));
            assertEquals("更新的基地", title.getText());
            checkView.run();

            writeLibraryWorld(base, "恢复的基地", 1100);
            refresh.fire();
            awaitFx(() -> !refresh.isDisabled());
            assertEquals("恢复的基地", title.getText());
            assertFalse(status.getText().contains("未完成"));
            checkView.run();

            Files.move(base, home.resolve("moved-world"));
            refresh.fire();
            awaitFx(() -> !refresh.isDisabled());
            assertTrue(status.getText().contains("当前世界已不在此目录"));
            assertEquals("选择一个世界", title.getText());
            assertNull(viewport.viewportState());
            assertTrue(viewport.viewerLockedSelection().isEmpty());
            assertNull(tree.getSelectionModel().getSelectedItem());
        });
    }

    private static List<String> libraryFolders(TreeItem<WorldTreeNode> group) {
        return group.getChildren().stream()
                .map(item -> item.getValue().getWorldInfo().getFolderPath().getFileName().toString()).toList();
    }

    private static void writeLibraryWorld(Path world, String name, long played) throws Exception {
        Files.createDirectories(world.resolve("region"));
        var data = net.kyori.adventure.nbt.CompoundBinaryTag.builder()
                .putString("LevelName", name).putLong("LastPlayed", played).build();
        var root = net.kyori.adventure.nbt.CompoundBinaryTag.builder().put("Data", data).build();
        net.kyori.adventure.nbt.BinaryTagIO.writer().write(root, world.resolve("level.dat"),
                net.kyori.adventure.nbt.BinaryTagIO.Compression.GZIP);
    }

    @Test
    void loadsMapFirstAndKeepsGroupedActionsReachableAtMinimumSize() throws Exception {
        withWorkspace(root -> {
            assertNull(root.lookup(".tab-pane"), "地图不应藏在标签页中");
            assertFalse(((ScrollPane) root.lookup("#detailsPane")).isVisible());
            assertTrue(((ToggleButton) root.lookup("#viewerModeButton")).isDisabled());
            assertTrue(((MenuButton) root.lookup("#locateMenuButton")).isDisabled());
            MenuButton layers = (MenuButton) root.lookup("#layersMenuButton");
            Parent layerContent = (Parent) ((CustomMenuItem) layers.getItems().getFirst()).getContent();
            for (String id : new String[]{"layerHeightSlider", "surfaceOverviewButton",
                    "playerMarkerButton", "spawnMarkerButton", "portalMarkerButton"}) {
                assertNotNull(layerContent.lookup("#" + id), "缺少已有图层入口：" + id);
            }
            for (String id : new String[]{"viewerModeButton", "locateMenuButton", "moreMenuButton",
                    "layersMenuButton", "playerListButton"}) {
                var node = root.lookup("#" + id);
                var bounds = node.localToScene(node.getBoundsInLocal());
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 901, id + " 超出窗口");
                assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= 601, id + " 超出窗口");
            }
            assertTrue(root.lookup("#viewportHost").getBoundsInLocal().getHeight() >= 250);
            snapshotIfRequested(root, "workspace-900x600.png");
        });
    }

    @Test
    void appearanceKeepsViewportAndUpdatesOwnedDialogAndToolsPopup() throws Exception {
        withWorkspace(root -> {
            var stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(root.getScene());
            stage.show();
            var about = AboutDialog.create(stage, ignored -> {});
            try {
                MenuButton tools = (MenuButton) root.lookup("#moreMenuButton");
                var appearance = (Menu) tools.getItems().stream()
                        .filter(item -> "appearanceMenu".equals(item.getId())).findFirst().orElseThrow();
                var light = (RadioMenuItem) appearance.getItems().get(0);
                var dark = (RadioMenuItem) appearance.getItems().get(1);
                assertTrue(light.isSelected());
                loadEmptyTestWorld(root);
                var viewport = root.lookup("#viewportHost");
                root.layout();
                double width = viewport.getLayoutBounds().getWidth();
                double height = viewport.getLayoutBounds().getHeight();
                var refresh = (Button) root.lookup("#refreshLibraryButton");
                var icon = assertInstanceOf(org.kordamp.ikonli.javafx.FontIcon.class, refresh.getGraphic());
                assertEquals("fth-refresh-cw", icon.getIconLiteral());
                assertEquals("刷新存档库", refresh.getAccessibleText());
                about.initModality(javafx.stage.Modality.NONE);
                about.show();
                dark.fire();
                root.applyCss();
                root.layout();
                about.getDialogPane().applyCss();
                assertTrue(dark.isSelected());
                assertTrue(new PortableSettings().usesDarkTheme());
                assertSame(viewport, root.lookup("#viewportHost"));
                assertEquals(width, viewport.getLayoutBounds().getWidth());
                assertEquals(height, viewport.getLayoutBounds().getHeight());
                var version = (Label) about.getDialogPane().lookup("#aboutVersion");
                assertEquals(javafx.scene.paint.Color.web("#a9b6c4"), version.getTextFill());
                tools.show();
                var popup = menuContent(tools).getScene().getRoot();
                popup.applyCss();
                var heading = (Label) menuContent(tools).lookup(".section-title");
                assertEquals(javafx.scene.paint.Color.web("#e6edf3"), heading.getTextFill());
                tools.hide();
            } finally {
                about.close();
                stage.close();
            }
        });
        withWorkspace(root -> {
            var tools = (MenuButton) root.lookup("#moreMenuButton");
            var appearance = (Menu) tools.getItems().stream().filter(item -> "appearanceMenu".equals(item.getId())).findFirst().orElseThrow();
            assertTrue(((RadioMenuItem) appearance.getItems().get(1)).isSelected());
            var sidebar = (javafx.scene.layout.Region) root.lookup("#worldSidebar");
            assertEquals(javafx.scene.paint.Color.web("#161b22"), sidebar.getBackground().getFills().getFirst().getFill());
            appearance.getItems().getFirst().fire();
            root.applyCss();
            assertEquals(javafx.scene.paint.Color.web("#f4f6f8"), sidebar.getBackground().getFills().getFirst().getFill());
            assertFalse(new PortableSettings().usesDarkTheme());
        });
    }





    private javafx.scene.control.Dialog<?> openResourceDialog(Parent root,MapViewerController controller)throws Exception {
        var entry=(Button)root.lookup("#resourceSettingsButton");
        if (entry.isVisible()) {
            assertFalse(entry.isDisabled());
            entry.fire();
        } else {
            var tools = (MenuButton) root.lookup("#moreMenuButton");
            tools.show();
            var resource = tools.getItems().stream().filter(item -> "resourceSettingsItem".equals(item.getId())).findFirst().orElseThrow();
            assertTrue(resource.isVisible() && !resource.isDisable());
            resource.fire();
            assertFalse(tools.isShowing());
        }
        var field=MapViewerController.class.getDeclaredField("resourceSettingsDialog");field.setAccessible(true);var dialog=(javafx.scene.control.Dialog<?>)field.get(controller);
        assertTrue(dialog.isShowing());dialog.getDialogPane().applyCss();dialog.getDialogPane().layout();return dialog;
    }



    @Test void resourceSettingsFollowsTaskCompletionStatus()throws Exception {
        withWorkspace(root->{
            loadEmptyTestWorld(root);Stage stage=new Stage();stage.setScene(root.getScene());stage.show();
            try{
                var controller=(MapViewerController)root.getProperties().get("mapViewerController");
                ((ToggleButton)root.lookup("#viewerModeButton")).fire();
                var viewport=(MapViewport)((StackPane)root.lookup("#viewportHost")).getChildren().getFirst();
                var selection=com.mcworldexplorer.voxel.data.ChunkRectangle.centered(0,0,3,3);
                var locked=MapViewerController.class.getDeclaredField("viewerLockedSelection");locked.setAccessible(true);locked.set(controller,selection);
                viewport.lockViewerSelection(selection);
                var dialog=openResourceDialog(root,controller);var pane=dialog.getDialogPane();
                var taskUi=MapViewerController.class.getDeclaredMethod("setViewerTaskUi",boolean.class);taskUi.setAccessible(true);taskUi.invoke(controller,true);
                var status=(Label)pane.lookup("#resourceSettingsStatus");assertTrue(status.getText().contains("正在生成预览"));
                assertEquals("取消生成",((Button)root.lookup("#viewerCancelButton")).getText());
                assertTrue(((Button)pane.lookup("#resourceAutomaticButton")).isDisabled());taskUi.invoke(controller,false);
                assertEquals(selection,viewport.viewerLockedSelection().orElseThrow());
                assertEquals("取消选区",((Button)root.lookup("#viewerCancelButton")).getText());
                ((Label)root.lookup("#viewerSelectionLabel")).setText("三维预览已打开；状态更新测试");
                assertEquals("三维预览已打开；状态更新测试",status.getText());assertFalse(((Button)pane.lookup("#resourceAutomaticButton")).isDisabled());dialog.close();
            }finally{stage.close();}
        });
    }

    @Test void resourceSettingsWrapLongPathsAndKeepActionsReachable()throws Exception {
        withWorkspace(root->{
            String longPath="D:\\游戏目录\\"+"整合包与资源目录\\".repeat(15)+"1.21.1.jar";
            var pane=new ResourceSettingsPane(new ResourceSettingsPane.Host(){
                public String world(){return "长路径布局测试存档";}public String version(){return "1.21.1";}public String client(){return longPath;}public String instance(){return longPath;}
                public String status(){return "已选择资源；生成预览后显示详细诊断。";}public String diagnostics(){return "此页面使用合成路径检查布局。";}public boolean resourceActionsAvailable(){return true;}
                public void automatic(){}public void chooseClient(){}public void chooseInstance(){}public void simpleColors(){}
            });
            var scene=new Scene(pane,520,440);scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());pane.applyCss();pane.resize(520,440);pane.layout();
            var manual = (TitledPane) pane.lookup("#resourceManualSection");
            manual.setAnimated(false);
            manual.setExpanded(true);
            pane.applyCss();pane.layout();
            var client=(Label)pane.lookup("#resourceClientValue");assertEquals(longPath,client.getTooltip().getText());assertTrue(client.getHeight()>30);
            var scroll=(ScrollPane)pane.lookup(".settings-scroll");assertTrue(scroll.getContent().getBoundsInParent().getWidth()<=scroll.getViewportBounds().getWidth()+1);
            scroll.setVvalue(1);pane.layout();snapshotIfRequested(pane,"resource-settings-long-path-520x440.png");
        });
    }

    @Test void pluginFeedbackStaysReadableAfterOpeningThePluginTab() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Stage stage = new Stage();
            stage.setScene(root.getScene());
            stage.show();
            try {
                var controller = (MapViewerController) root.getProperties().get("mapViewerController");
                var dialog = openResourceDialog(root, controller);
                try {
                    var pane = dialog.getDialogPane();
                    var settings = (ResourceSettingsPane) pane.getContent();
                    ((javafx.scene.control.TabPane) pane.lookup("#resourceSettingsTabs")).getSelectionModel().select(2);
                    settings.showPluginFeedback("无法应用：插件进程启动失败");
                    pane.applyCss();
                    pane.layout();
                    var feedback = (javafx.scene.control.TextArea) pane.lookup("#modelPluginsFeedback");
                    assertTrue(feedback.isVisible());
                    assertTrue(feedback.getHeight() >= feedback.prefHeight(-1), "插件结果必须保留可读的显示高度");
                } finally {
                    dialog.close();
                }
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void switchingWorldOrChoosingSimpleColorsDiscardsAnOlderLookup() throws Exception {
        withWorkspace(root -> {
            MapViewerController controller=(MapViewerController)root.getProperties().get("mapViewerController");
            var roots=MapViewerController.class.getDeclaredField("clientResourceRoots");roots.setAccessible(true);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            roots.set(controller,(java.util.function.Supplier<java.util.List<Path>>)()->{
                entered.countDown();
                try { release.await(3,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return java.util.List.of(home);
            });
            WorldInfo first=new WorldInfo(home.resolve("first"));first.setParsed(true);first.setVersionName("1.21.1");
            controller.setWorld(first);
            awaitFx(()->entered.getCount()==0);
            MenuButton menu=(MenuButton)root.lookup("#viewerTextureMenu");
            menu.getItems().getLast().fire();
            assertEquals("纹理：简化颜色",menu.getText());
            WorldInfo second=new WorldInfo(home.resolve("second"));second.setParsed(true);second.setVersionName("1.20.1");
            controller.setWorld(second);release.countDown();
            awaitFx(()->menu.getTooltip()!=null && menu.getTooltip().getText().contains("1.20.1"));
            assertEquals("纹理：简化颜色",menu.getText());
        });
    }

    @Test
    void realWorldAutomaticallySelectsItsClientAndCanReturnToAutomaticMode() throws Exception {
        String configured=System.getenv("MCWORLD_UI_TEST_WORLD");
        assumeTrue(configured!=null && !configured.isBlank());
        withWorkspace(root -> {
            selectWorld(root, LevelDatReader.readLevelDat(Path.of(configured)));
            MenuButton menu=(MenuButton)root.lookup("#viewerTextureMenu");
            assertEquals("纹理：自动 · 1.21.1",menu.getText());
            assertTrue(menu.getTooltip().getText().contains("1.21.1.jar"));
            ((ToggleButton)root.lookup("#viewerModeButton")).fire();
            root.applyCss(); root.layout();
            var viewport=(MapViewport)((StackPane)root.lookup("#viewportHost")).getChildren().getFirst();
            awaitFx(viewport::hasCompleteTargetCoverage);
            snapshotIfRequested(root,"resource-auto-matched.png");
            String configuredJar=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
            if (configuredJar != null) {
                var controller=(MapViewerController)root.getProperties().get("mapViewerController");
                var lookup=MapViewerController.class.getDeclaredMethod("startViewerResourceLookup",Path.class);
                lookup.setAccessible(true);lookup.invoke(controller,Path.of(configuredJar));
                awaitFx(()->menu.getText().equals("纹理：原版 1.21.1"));
                assertEquals(Path.of(configuredJar).toRealPath(),new PortableSettings().loadClientResourcePath(Path.of(configured),"1.21.1").orElseThrow());
            }
            menu.getItems().getLast().fire();
            assertEquals("纹理：简化颜色",menu.getText());
            menu.getItems().get(1).fire();
            awaitFx(() -> menu.getText().equals("纹理：自动 · 1.21.1"));
            assertTrue(menu.getTooltip().getText().contains("自动匹配"));
            assertTrue(new PortableSettings().loadClientResourcePath(Path.of(configured),"1.21.1").isEmpty());
        });
    }

    @Test void aCompletedOlderManualLookupCannotPersistAfterReturningToAutomatic()throws Exception {
        String client=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(client!=null);
        withWorkspace(root->{
            var controller=(MapViewerController)root.getProperties().get("mapViewerController");
            Path path=Files.createDirectories(home.resolve("world"));WorldInfo info=new WorldInfo(path);info.setParsed(true);info.setVersionName("1.21.1");controller.setWorld(info);
            var lookup=MapViewerController.class.getDeclaredMethod("startViewerResourceLookup",Path.class);lookup.setAccessible(true);lookup.invoke(controller,Path.of(client));
            var field=MapViewerController.class.getDeclaredField("viewerResourceLookup");field.setAccessible(true);
            var completed=(javafx.concurrent.Task<?>)field.get(controller);
            // Finish background validation while the FX success callback is still queued.
            completed.get(10,TimeUnit.SECONDS);
            assertTrue(new PortableSettings().loadClientResourcePath(path,"1.21.1").isEmpty(),"后台校验完成本身不能写绑定");
            ((MenuButton)root.lookup("#viewerTextureMenu")).getItems().get(1).fire();
            awaitFx(()->{try{return field.get(controller)==null;}catch(IllegalAccessException error){throw new AssertionError(error);}});
            assertTrue(new PortableSettings().loadClientResourcePath(path,"1.21.1").isEmpty(),"旧手动结果不能恢复已清除绑定");
        });
    }

    @Test void automaticResourceMatchingResetsBothManualChoicesOnlyForCurrentWorld()throws Exception {
        withWorkspace(root->{
            var controller=(MapViewerController)root.getProperties().get("mapViewerController");
            Path first=Files.createDirectories(home.resolve("world-a")),second=Files.createDirectories(home.resolve("world-b")),instance=Files.createDirectories(home.resolve("实例/mods")).getParent();
            WorldInfo a=new WorldInfo(first);a.setParsed(true);a.setVersionName("1.21.1");
            WorldInfo b=new WorldInfo(second);b.setParsed(true);b.setVersionName("1.21.1");
            var change=MapViewerController.class.getDeclaredMethod("changeResourceInstance",Path.class);change.setAccessible(true);
            var selected=MapViewerController.class.getDeclaredField("viewerResourceInstance");selected.setAccessible(true);
            var settings=new PortableSettings();Path jar=home.resolve("manual-client.jar");
            settings.saveClientResourcePath(first,"1.21.1",jar);settings.saveClientResourcePath(second,"1.21.1",jar);
            settings.saveResourceInstancePath(second,"1.21.1",instance);
            controller.setWorld(a);change.invoke(controller,instance);
            assertEquals(instance,new PortableSettings().loadResourceInstancePath(first,"1.21.1").orElseThrow());
            controller.setWorld(b);assertEquals(instance,selected.get(controller));controller.setWorld(a);assertEquals(instance,selected.get(controller));
            MenuButton menu=(MenuButton)root.lookup("#viewerTextureMenu");assertEquals("自动匹配资源",menu.getItems().get(1).getText());
            menu.getItems().get(1).fire();assertNull(selected.get(controller));
            assertTrue(settings.loadResourceInstancePath(first,"1.21.1").isEmpty());assertTrue(settings.loadClientResourcePath(first,"1.21.1").isEmpty());
            assertEquals(instance,settings.loadResourceInstancePath(second,"1.21.1").orElseThrow());assertEquals(jar,settings.loadClientResourcePath(second,"1.21.1").orElseThrow());
            controller.setWorld(b);controller.setWorld(a);assertNull(selected.get(controller),"重开世界后仍使用自动游戏目录");
        });
    }

    @Test
    void textureMenuFollowsExistingViewerToolbarAndKeepsSimpleMode() throws Exception {
        withWorkspace(root -> {
            // This is a toolbar layout test; do not start unrelated asynchronous map writes.
            root.lookup("#viewerSelectionBar").setManaged(true);
            root.lookup("#viewerSelectionBar").setVisible(true);
            ((javafx.scene.control.TitledPane)root.lookup("#viewerAdvancedOptions")).setExpanded(true);
            MenuButton menu = (MenuButton) root.lookup("#viewerTextureMenu");
            assertNotNull(menu);
            assertEquals("纹理：自动", menu.getText());
            Label help = (Label) root.lookup("#viewerTextureHelp");
            assertNotNull(help);
            assertEquals(menu.getParent(), help.getParent(), "帮助入口须与纹理菜单一起换行");
            assertNotNull(help.getTooltip());
            assertTrue(help.getTooltip().isWrapText());
            assertTrue(help.getTooltip().getText().contains(".minecraft\\versions\\1.21.1\\1.21.1.jar"));
            assertTrue(help.getTooltip().getText().contains("按存档版本尝试加载"));
            assertEquals(4, menu.getItems().size());
            assertEquals("自动匹配资源",menu.getItems().get(1).getText());
            assertEquals("手动选择游戏目录…",menu.getItems().get(2).getText());
            assertTrue(menu.getItems().stream().noneMatch(item->item.getText().contains("实例")||item.getText().contains("恢复")),"统一自动入口后不再拆分恢复操作");
            assertTrue(menu.getItems().stream().noneMatch(item->item.getText().contains("诊断")),"诊断入口应独立于纹理选择下拉栏");
            Button diagnostics=(Button)root.lookup("#viewerResourceDiagnosticsButton");
            assertNotNull(diagnostics);assertEquals("查看解析诊断",diagnostics.getText());assertNotNull(diagnostics.getOnAction());
            assertEquals(menu.getParent(),diagnostics.getParent(),"资源操作应成组换行");
            menu.setDisable(true);assertTrue(diagnostics.isDisabled(),"生成期间与纹理操作保持相同禁用状态");
            menu.setDisable(false);assertFalse(diagnostics.isDisabled());
            assertEquals("手动选择 JAR", menu.getItems().getFirst().getText());
            assertNotNull(menu.getItems().getFirst().getOnAction());
            menu.getItems().getLast().fire();
            assertEquals("纹理：简化颜色", menu.getText());
            root.applyCss(); root.layout();
            var bounds = menu.localToScene(menu.getBoundsInLocal());
            assertTrue(bounds.getMaxX() <= 901);
            var diagnosticBounds=diagnostics.localToScene(diagnostics.getBoundsInLocal());
            var helpLayoutBounds=help.localToScene(help.getBoundsInLocal());
            assertTrue(diagnosticBounds.getMaxX()<=901,"独立诊断按钮不能溢出窄窗口");
            assertTrue(diagnosticBounds.getMinX()>=helpLayoutBounds.getMaxX()+5,"诊断按钮与帮助入口须留间距");
            assertEquals(helpLayoutBounds.getCenterY(),diagnosticBounds.getCenterY(),1,"资源操作垂直居中");
            var diagnosticText=(javafx.scene.text.Text)diagnostics.lookup(".text");
            assertEquals(diagnostics.getText(),diagnosticText.getText(),"诊断按钮文字完整显示");
            assertTrue(root.lookup("#viewportHost").getBoundsInLocal().getHeight() >= 160);
            snapshotIfRequested(root, "texture-toolbar-900x600.png");
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            stage.setScene(root.getScene());
            try {
                stage.show();
                menu.show();
                for (Window window : java.util.List.copyOf(Window.getWindows())) {
                    if (window instanceof PopupWindow && window.isShowing()) {
                        window.setOpacity(0);
                        Parent popup = window.getScene().getRoot();
                        popup.applyCss(); popup.layout();
                        for (var node : popup.lookupAll(".menu-item .label")) {
                            Label label = (Label) node;
                            var rendered = (javafx.scene.text.Text) label.lookup(".text");
                            assertEquals(label.getText(), rendered.getText(), "纹理菜单文字不能被省略");
                            assertTrue(label.getWidth() + 1 >= label.prefWidth(-1), "纹理菜单宽度不足");
                            assertTrue(label.getHeight() + 1 >= label.prefHeight(-1), "纹理菜单高度不足");
                        }
                        snapshotIfRequested(popup,"texture-menu-expanded.png");
                    }
                }
                menu.hide();
                help.fireEvent(new MouseEvent(MouseEvent.MOUSE_ENTERED, 0, 0, 0, 0,
                        MouseButton.NONE, 0, false, false, false, false,
                        false, false, false, false, false, false, null));
                assertTrue(help.getTooltip().isShowing());
                var helpBounds = help.localToScreen(help.getBoundsInLocal());
                var helpTip = help.getTooltip();
                assertTrue(helpTip.getY() >= helpBounds.getMaxY() + 7
                        || helpTip.getY() + helpTip.getHeight() <= helpBounds.getMinY() - 7,
                        "气泡不得覆盖问号，避免触发反复进出");
                help.getTooltip().setOpacity(0);
                Parent tip = help.getTooltip().getScene().getRoot();
                tip.applyCss(); tip.layout();
                assertTrue(tip.getLayoutBounds().getWidth() <= 361, "气泡须按最大宽度换行");
                assertTrue(tip.getLayoutBounds().getHeight() > 100, "帮助内容须完整展开");
                snapshotIfRequested(tip, "texture-help-tooltip.png");
                var hiddenDuringHover = new java.util.concurrent.atomic.AtomicBoolean();
                long hoverUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                awaitFx(() -> {
                    if (!helpTip.isShowing()) hiddenDuringHover.set(true);
                    return System.nanoTime() >= hoverUntil;
                });
                assertFalse(hiddenDuringHover.get(), "持续悬停超过默认显示时长仍应保持气泡");
                help.fireEvent(new MouseEvent(MouseEvent.MOUSE_EXITED, 0, 0, 0, 0,
                        MouseButton.NONE, 0, false, false, false, false,
                        false, false, false, false, false, false, null));
                assertFalse(helpTip.isShowing(), "离开问号后应关闭气泡");
            } finally { help.getTooltip().hide(); menu.hide(); stage.close(); }

        });
    }

    @Test
    void openingDetailsDoesNotResizeMapAndSidebarCanBeRestored() throws Exception {
        withWorkspace(root -> {
            double mapWidth = root.lookup("#viewportHost").getBoundsInLocal().getWidth();
            ToggleButton details = (ToggleButton) root.lookup("#detailsToggleButton");
            ScrollPane panel = (ScrollPane) root.lookup("#detailsPane");
            details.fire();
            root.layout();
            assertTrue(panel.isVisible());
            assertTrue(panel.isManaged());
            assertEquals(mapWidth, root.lookup("#viewportHost").getBoundsInLocal().getWidth(), .1);
            snapshotIfRequested(root, "workspace-details-900x600.png");
            details.fire();
            assertFalse(panel.isVisible());
            SplitPane split = (SplitPane) root;
            Button sidebar = (Button) root.lookup("#sidebarToggleButton");
            sidebar.fire();
            root.layout();
            assertEquals(1, split.getItems().size());
            assertTrue(root.lookup("#viewportHost").getBoundsInLocal().getWidth() > mapWidth);
            sidebar.fire();
            root.layout();
            assertEquals(2, split.getItems().size());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void narrowingToolbarAndReversingDetailsKeepMapAndActionsReachable() throws Exception {
        withWorkspace(root -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(root.getScene());
            stage.show();
            try {
                loadEmptyTestWorld(root);
                Label summary = (Label) root.lookup("#worldSummaryLabel");
                assertEquals("1.21.1 · 只读浏览", summary.getText());
                var path = (Label) root.lookup("#folderPathLabel");
                assertEquals(home.resolve("empty-test-world").toString(), path.getText());
                var toolbar = (MapToolbar) root.lookup("#mapToolbar");
                Button resource = (Button) root.lookup("#resourceSettingsButton");
                MenuButton tools = (MenuButton) root.lookup("#moreMenuButton");
                MenuItem moved = tools.getItems().stream().filter(i -> "resourceSettingsItem".equals(i.getId())).findFirst().orElseThrow();
                stage.setWidth(1200);
                awaitFx(() -> resource.isVisible() && toolbar.getWidth() > 800);
                assertFalse(moved.isVisible());
                stage.setWidth(900);
                awaitFx(() -> root.getLayoutBounds().getWidth() <= 900);
                ((SplitPane) root).setDividerPositions(0.5);
                awaitFx(() -> !resource.isVisible());
                assertTrue(moved.isVisible());
                assertEquals(tools.getBoundsInParent().getCenterY(),
                        root.lookup("#viewerModeButton").getBoundsInParent().getCenterY(), 2);
                openResourceDialog(root,(MapViewerController) root.getProperties().get("mapViewerController")).close();
                var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                ((ToggleButton) root.lookup("#viewerModeButton")).fire();
                var ranges = (ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption>) root.lookup("#viewerRangeComboBox");
                ranges.setValue(com.mcworldexplorer.viewer.ViewerRangeOption.FREE);
                root.applyCss();
                root.layout();
                drag(viewport.lookup("Canvas"), 90, 65, 106, 77, MouseButton.PRIMARY);
                var locked = viewport.viewerLockedSelection().orElseThrow();
                ToggleButton toggle = (ToggleButton) root.lookup("#detailsToggleButton");
                ScrollPane panel = (ScrollPane) root.lookup("#detailsPane");
                double width = viewport.getWidth(), height = viewport.getHeight();
                toggle.fire();
                awaitFx(() -> panel.getOpacity() > 0);
                toggle.fire();
                assertTrue(panel.isMouseTransparent());
                toggle.fire();
                awaitFx(() -> panel.getOpacity() == 1);
                assertFalse(panel.isMouseTransparent());
                assertEquals(0, panel.getTranslateX(), .01);
                var clipboard = Clipboard.getSystemClipboard();
                var previous = new ClipboardContent();
                for (var type : clipboard.getContentTypes()) {
                    var value = clipboard.getContent(type);
                    if (value != null) previous.put(type, value);
                }
                try {
                    ((Button) panel.lookup("#copyPathButton")).fire();
                    awaitFx(() -> path.getText().equals(clipboard.getString()));
                } finally {
                    if (path.getText().equals(clipboard.getString())) clipboard.setContent(previous);
                }
                viewport.centerOn(23, 41);
                Event.fireEvent(root, escapeKey());
                awaitFx(() -> !panel.isVisible());
                assertFalse(panel.isManaged());
                assertSame(toggle, root.getScene().getFocusOwner());
                assertEquals(23, viewport.viewportState().centerX(), .01);
                assertEquals(locked, viewport.viewerLockedSelection().orElseThrow());
                assertEquals(width, viewport.getWidth(), .01);
                assertEquals(height, viewport.getHeight(), .01);
                stage.setWidth(1200);
                awaitFx(resource::isVisible);
                assertFalse(moved.isVisible());
            } finally {
                stage.close();
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidWorldShowsParseFailureAndNeverEnablesThreeDimensionalAction() throws Exception {
        withWorkspace(root -> {
            WorldInfo invalid = new WorldInfo(home.resolve("broken-world"));
            invalid.setLevelName("无法解析的测试世界");
            TreeView<WorldTreeNode> tree = (TreeView<WorldTreeNode>) root.lookup("#worldTreeView");
            TreeItem<WorldTreeNode> item = new TreeItem<>(WorldTreeNode.world(invalid));
            tree.getRoot().getChildren().add(item);
            tree.getSelectionModel().select(item);
            assertEquals(invalid.getLevelName(), ((Label) root.lookup("#worldNameLabel")).getText());
            assertTrue(((Label) root.lookup("#worldSummaryLabel")).getText().contains("解析失败"));
            assertTrue(((Label) root.lookup("#statusLabel")).getText().contains("无法生成地图"));
            assertTrue(((ToggleButton) root.lookup("#viewerModeButton")).isDisabled());
        });
    }

    @Test
    void longWorldNameRemainsReadableAndEscapeOnlyDismissesOpenDetails() throws Exception {
        withWorkspace(root -> {
            WorldInfo world = new WorldInfo(home.resolve("long-name-world"));
            String name = "很久以前建造的山谷基地与地下交通枢纽".repeat(8);
            world.setLevelName(name);
            selectWorld(root, world);
            root.layout();
            Label title = (Label) root.lookup("#worldNameLabel");
            assertEquals("未知 · 解析失败 · long-name-world", ((Label) root.lookup("#worldSummaryLabel")).getText());
            assertNotNull(title.getTooltip(), "截断的世界名称必须能读取全文");
            assertEquals(name, title.getTooltip().getText());
            Button sidebar = (Button) root.lookup("#sidebarToggleButton");
            assertTrue(sidebar.getWidth() >= sidebar.prefWidth(-1), "长摘要不能挤掉世界列表按钮文字");
            ToggleButton details = (ToggleButton) root.lookup("#detailsToggleButton");
            assertTrue(details.getWidth() >= details.prefWidth(-1), "长摘要不能挤掉存档详情按钮文字");
            details.fire();
            Event.fireEvent(root, escapeKey());
            assertFalse(details.isSelected(), "Esc 应关闭已展开的详情");
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
            root.addEventHandler(KeyEvent.KEY_PRESSED, e -> delivered.set(true));
            Event.fireEvent(root, escapeKey());
            assertTrue(delivered.get(), "详情关闭后不能吞掉地图的 Esc");
            assertTrue(root.lookup("#detailsToggleButton").localToScene(
                    root.lookup("#detailsToggleButton").getBoundsInLocal()).getMaxX() <= 901);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadedWorldKeepsLayersMarkersLocationAndSelectionConnected() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            MenuButton layersMenu = (MenuButton) root.lookup("#layersMenuButton");
            Parent layers = menuContent(layersMenu);
            Slider height = (Slider) layers.lookup("#layerHeightSlider");
            assertEquals(-64, height.getMin());
            assertEquals(319, height.getMax());
            height.setValue(96);
            assertTrue(((Label) layers.lookup("#layerRangeLabel")).getText().contains("96"));
            assertFalse(((ToggleButton) layers.lookup("#surfaceOverviewButton")).isSelected());
            MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            ToggleButton player = (ToggleButton) layers.lookup("#playerMarkerButton");
            player.fire();
            assertFalse(viewport.showPlayerProperty().get());
            player.fire();
            assertTrue(viewport.showPlayerProperty().get());
            Parent coordinates = menuContent((MenuButton) root.lookup("#locateMenuButton"));
            TextField x = (TextField) coordinates.lookup("#xField");
            TextField z = (TextField) coordinates.lookup("#zField");
            x.setText("not-a-coordinate");
            Event.fireEvent(x, new ActionEvent());
            assertFalse(((Label) coordinates.lookup("#coordinateErrorLabel")).getText().isBlank());
            x.setText("64");
            z.setText("-32");
            Event.fireEvent(x, new ActionEvent());
            assertEquals(64, viewport.viewportState().centerX());
            assertEquals(-32, viewport.viewportState().centerZ());
            ToggleButton mode = (ToggleButton) root.lookup("#viewerModeButton");
            mode.fire();
            assertTrue(root.lookup("#viewerSelectionBar").isVisible());
            var canvas = viewport.lookup("Canvas");
            assertNotNull(canvas);
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_MOVED, MouseButton.NONE));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY));
            Event.fireEvent(canvas, mouse(MouseEvent.MOUSE_RELEASED, MouseButton.PRIMARY));
            assertFalse(((Button) root.lookup("#viewerConfirmButton")).isDisabled());
            Event.fireEvent(viewport, escapeKey());
            assertTrue(mode.isSelected(), "第一次 Esc 只清除锁定区域");
            Event.fireEvent(viewport, escapeKey());
            assertFalse(mode.isSelected(), "第二次 Esc 退出三维选区");
            ComboBox<?> dimensions = (ComboBox<?>) root.lookup("#dimensionComboBox");
            assertEquals(3, dimensions.getItems().size());
            dimensions.getSelectionModel().select(1);
            awaitFx(() -> !height.isDisabled());
            assertEquals(0, height.getMin());
            assertEquals(255, height.getMax());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void freeSelectionSupportsRectanglesLimitsPanningAndProfileChanges() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost"))
                    .getChildren().getFirst();
            ((ToggleButton) root.lookup("#viewerModeButton")).fire();
            ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption> ranges =
                    (ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption>) root.lookup("#viewerRangeComboBox");
            ComboBox<com.mcworldexplorer.viewer.ViewerPerformanceProfile> profiles =
                    (ComboBox<com.mcworldexplorer.viewer.ViewerPerformanceProfile>) root.lookup("#viewerProfileComboBox");
            ranges.setValue(com.mcworldexplorer.viewer.ViewerRangeOption.FREE);
            root.applyCss();
            root.layout();
            viewport.viewportState().zoomVisualAt(1, 0, 0, viewport.getWidth(), viewport.getHeight());
            viewport.viewportState().centerOn(0, 0);
            Button confirm = (Button) root.lookup("#viewerConfirmButton");
            Button cancel = (Button) root.lookup("#viewerCancelButton");
            Label feedback = (Label) root.lookup("#viewerSelectionLabel");
            var canvas = viewport.lookup("Canvas");
            double originalX = viewport.viewportState().centerX();
            drag(canvas, 100, 80, 340, 192, MouseButton.PRIMARY);
            assertTrue(confirm.isDisabled());
            assertTrue(feedback.getText().contains("超出上限"));
            assertTrue(viewport.viewerLockedSelection().isEmpty());
            assertEquals(originalX, viewport.viewportState().centerX(), "框选不能平移地图");
            snapshotIfRequested(root, "free-selection-over-limit.png");
            profiles.setValue(com.mcworldexplorer.viewer.ViewerPerformanceProfile.ENHANCED);
            assertEquals(com.mcworldexplorer.viewer.ViewerRangeOption.FREE, ranges.getValue());
            drag(canvas, 340, 192, 100, 80, MouseButton.PRIMARY);
            var rectangle = viewport.viewerLockedSelection().orElseThrow();
            assertTrue(rectangle.width() > rectangle.depth());
            assertTrue(rectangle.width() * rectangle.depth() > 64);
            assertTrue(rectangle.minX() < 0 && rectangle.minZ() < 0);
            assertFalse(confirm.isDisabled());
            snapshotIfRequested(root, "free-selection-rectangle.png");
            drag(canvas, 100, 80, 132, 96, MouseButton.SECONDARY);
            assertEquals(originalX - 32, viewport.viewportState().centerX());
            assertEquals(rectangle, viewport.viewerLockedSelection().orElseThrow());
            drag(canvas, 100, 70, 650, 380, MouseButton.PRIMARY);
            assertEquals(rectangle, viewport.viewerLockedSelection().orElseThrow(),
                    "已锁定区域只能经取消或 Esc 解锁，不能直接重画");
            assertFalse(confirm.isDisabled());
            assertTrue(cancel.isVisible());
            assertEquals("取消选区", cancel.getText());
            cancel.fire();
            assertTrue(viewport.viewerLockedSelection().isEmpty());
            assertTrue(confirm.isDisabled());
            drag(canvas, 100, 70, 650, 380, MouseButton.PRIMARY);
            assertTrue(confirm.isDisabled());
            assertTrue(feedback.getText().contains("超出上限"));
            drag(canvas, 160, 100, 160, 100, MouseButton.PRIMARY);
            assertEquals(1, viewport.viewerLockedSelection().orElseThrow().width());
            assertFalse(confirm.isDisabled());
            cancel.fire();
            assertTrue(viewport.viewerLockedSelection().isEmpty());
            profiles.setValue(com.mcworldexplorer.viewer.ViewerPerformanceProfile.BASIC);
            assertTrue(confirm.isDisabled());
            assertTrue(viewport.viewerLockedSelection().isEmpty());
            Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, 100, 80, MouseButton.PRIMARY));
            Event.fireEvent(viewport, escapeKey());
            Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, 340, 192, MouseButton.PRIMARY));
            assertFalse(viewport.isViewerSelectionMode());
            assertTrue(viewport.viewerLockedSelection().isEmpty());
        });
    }

    @Test
    void lockedSelectionSurvivesLoadingAndCompletionUntilExplicitCancellation() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            var controller = (MapViewerController) root.getProperties().get("mapViewerController");
            ((ToggleButton) root.lookup("#viewerModeButton")).fire();
            @SuppressWarnings("unchecked")
            var ranges = (ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption>) root.lookup("#viewerRangeComboBox");
            var profiles = (ComboBox<?>) root.lookup("#viewerProfileComboBox");
            ranges.setValue(com.mcworldexplorer.viewer.ViewerRangeOption.FREE);
            var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            root.applyCss(); root.layout();
            viewport.viewportState().zoomVisualAt(1, 0, 0, viewport.getWidth(), viewport.getHeight());
            viewport.viewportState().centerOn(0, 0);
            var canvas = viewport.lookup("Canvas");
            var confirm = (Button) root.lookup("#viewerConfirmButton");
            var cancel = (Button) root.lookup("#viewerCancelButton");
            var label = (Label) root.lookup("#viewerSelectionLabel");
            var running = MapViewerController.class.getDeclaredField("viewerTaskRunning"); running.setAccessible(true);
            var locked = MapViewerController.class.getDeclaredField("viewerLockedSelection"); locked.setAccessible(true);
            var target = MapViewerController.class.getDeclaredField("activeViewerTarget"); target.setAccessible(true);
            var requestId = MapViewerController.class.getDeclaredField("activeViewerRequestId"); requestId.setAccessible(true);
            var setUi = MapViewerController.class.getDeclaredMethod("setViewerTaskUi", boolean.class); setUi.setAccessible(true);
            var progress = MapViewerController.class.getDeclaredMethod("acceptViewerProgress", long.class, com.mcworldexplorer.viewer.ViewerProgress.class); progress.setAccessible(true);
            var failure = MapViewerController.class.getDeclaredMethod("acceptViewerFailure", long.class, Throwable.class); failure.setAccessible(true);
            var success = MapViewerController.class.getDeclaredMethod("acceptViewerSuccess", long.class, com.mcworldexplorer.viewer.ViewerTaskResult.class); success.setAccessible(true);
            var original = com.mcworldexplorer.voxel.data.ChunkRectangle.centered(40, 40, 3, 3);
            var worldField = MapViewerController.class.getDeclaredField("world"); worldField.setAccessible(true);
            var world = ((WorldInfo) worldField.get(controller)).getFolderPath();
            var request = new com.mcworldexplorer.viewer.ViewerRequest(world, "fixture", "minecraft:overworld", "主世界", original,
                    com.mcworldexplorer.viewer.ViewerPerformanceProfile.BASIC,
                    new com.mcworldexplorer.viewer.ViewerMapReturnState(0, 0, 1, com.mcworldexplorer.preview.PreviewLayer.surfaceOverview()));
            var scene = new com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot(0, 0, java.util.List.of(),
                    new com.mcworldexplorer.voxel.mesh.MeshBounds(0, 0, 0, 0, 0, 0), 0, 0, 0, 0, java.util.List.of());
            var result = new com.mcworldexplorer.viewer.ViewerTaskResult(request, scene, false, 0);
            for (String completion : java.util.List.of("cancel", "escape", "success", "failure")) {
                viewport.viewportState().zoomAt(com.mcworldexplorer.map.MapDisplayZoom.BLOCKS_1,
                        viewport.getWidth() / 2, viewport.getHeight() / 2,
                        viewport.getWidth(), viewport.getHeight());
                locked.set(controller, original);
                viewport.lockViewerSelection(original);
                target.set(controller, original);
                requestId.setLong(controller, 123);
                setUi.invoke(controller, true);
                var spinner = (javafx.scene.control.ProgressIndicator) root.lookup("#viewerBusyIndicator");
                var bar = (javafx.scene.control.ProgressBar) root.lookup("#viewerProgressBar");
                assertTrue(spinner.isVisible() && spinner.isManaged() && spinner.isIndeterminate());
                assertFalse(bar.isVisible());
                label.setText("正在读取原区域");
                assertTrue(ranges.isDisabled(), "加载中不能更换选区范围");
                assertTrue(profiles.isDisabled(), "加载中不能更换性能档位");
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, 100, 80, MouseButton.PRIMARY));
                assertTrue(running.getBoolean(controller), "左键按下不能取消加载");
                assertEquals(original, target.get(controller)); assertEquals(123, requestId.getLong(controller));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, 132, 96, MouseButton.PRIMARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, 132, 96, MouseButton.PRIMARY));
                assertEquals(original, viewport.viewerLockedSelection().orElseThrow(), "加载中不能框选新区域");
                assertEquals(original, locked.get(controller));
                assertTrue(running.getBoolean(controller), "地图操作不能取消加载");
                assertEquals(original, target.get(controller)); assertEquals(123, requestId.getLong(controller));
                assertTrue(confirm.isDisabled()); assertTrue(cancel.isVisible()); assertEquals("正在读取原区域", label.getText());
                assertEquals("取消生成", cancel.getText());
                double centerBeforePan = viewport.viewportState().centerX();
                drag(canvas, 100, 80, 116, 80, MouseButton.SECONDARY);
                assertTrue(viewport.viewportState().centerX() < centerBeforePan, "加载中仍可拖动地图");
                var zoomBefore = viewport.viewportState().displayZoom();
                viewport.zoomIn();
                assertNotEquals(zoomBefore, viewport.viewportState().displayZoom(), "加载中仍可缩放地图");
                assertEquals(original, viewport.viewerLockedSelection().orElseThrow());
                progress.invoke(controller, 123L, new com.mcworldexplorer.viewer.ViewerProgress(com.mcworldexplorer.viewer.ViewerProgressStage.READING_CHUNKS, 1, 2, "原区域仍在加载"));
                assertEquals("原区域仍在加载", label.getText());
                var aligning = new com.mcworldexplorer.viewer.ViewerProgress(
                        com.mcworldexplorer.viewer.ViewerProgressStage.PREPARING_TEXTURES, 2, 5, "正在对齐纹理（方块状态） 2/5");
                progress.invoke(controller, 123L, aligning);
                assertEquals(aligning.message(), label.getText());
                assertTrue(spinner.isVisible() && bar.isVisible() && bar.isManaged());
                assertEquals(0.4, bar.getProgress());
                if (completion.equals("cancel")) cancel.fire();
                else if (completion.equals("escape")) Event.fireEvent(viewport, escapeKey());
                else if (completion.equals("failure")) {
                    failure.invoke(controller, 123L, new IllegalStateException("expected regression fixture"));
                } else {
                    success.invoke(controller, 123L, result);
                }
                assertFalse(running.getBoolean(controller));
                assertFalse(spinner.isVisible() || spinner.isManaged() || bar.isVisible() || bar.isManaged());
                assertEquals(-1, requestId.getLong(controller));
                if (completion.equals("cancel") || completion.equals("escape")) {
                    assertTrue(viewport.isViewerSelectionMode(), "取消生成只解除锁定，不退出三维选区模式");
                    assertTrue(viewport.viewerLockedSelection().isEmpty());
                    assertNull(locked.get(controller));
                    assertNull(target.get(controller));
                    assertTrue(confirm.isDisabled());
                    assertFalse(cancel.isVisible());
                    assertFalse(ranges.isDisabled());
                    assertFalse(profiles.isDisabled());
                    String cancelledLabel = label.getText();
                    progress.invoke(controller, 123L, new com.mcworldexplorer.viewer.ViewerProgress(
                            com.mcworldexplorer.viewer.ViewerProgressStage.READING_CHUNKS, 2, 2, "过期进度"));
                    success.invoke(controller, 123L, result);
                    failure.invoke(controller, 123L, new IllegalStateException("stale failure"));
                    assertEquals(cancelledLabel, label.getText(), "取消后的旧回调不能覆盖当前状态");
                    assertTrue(viewport.viewerLockedSelection().isEmpty(), "迟到成功不能复活已取消的选区");
                    assertNull(locked.get(controller));
                    assertNull(target.get(controller));
                    assertEquals(-1, requestId.getLong(controller));
                    assertFalse(running.getBoolean(controller));
                    assertTrue(confirm.isDisabled());
                } else {
                    assertEquals(original, viewport.viewerLockedSelection().orElseThrow(), "成功或失败后仍固定原区域");
                    assertEquals(original, locked.get(controller));
                    assertFalse(confirm.isDisabled(), "终态允许重新打开或重试原区域");
                    assertTrue(cancel.isVisible(), "锁定区域保留显式取消入口");
                    assertEquals("取消选区", cancel.getText());
                    assertTrue(ranges.isDisabled(), "调整范围前需显式取消选区");
                    assertTrue(profiles.isDisabled(), "调整档位前需显式取消选区");
                    if (completion.equals("failure")) assertEquals("重新生成", confirm.getText());
                    drag(canvas, 100, 80, 132, 96, MouseButton.PRIMARY);
                    assertEquals(original, viewport.viewerLockedSelection().orElseThrow(), "终态不能直接重画选区");
                    cancel.fire();
                    assertTrue(viewport.viewerLockedSelection().isEmpty());
                    assertNull(locked.get(controller));
                }
            }
        });
    }

    private static void drag(javafx.scene.Node canvas, double x1, double y1,
                             double x2, double y2, MouseButton button) {
        Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, x1, y1, button));
        Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, x2, y2, button));
        Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, x2, y2, button));
    }

    private static MouseEvent pointer(javafx.scene.Node canvas, javafx.event.EventType<MouseEvent> type,
                                      double x, double y, MouseButton button) {
        return pointer(canvas, type, x, y, button, type != MouseEvent.MOUSE_RELEASED && button == MouseButton.PRIMARY);
    }

    private static MouseEvent pointer(javafx.scene.Node canvas, javafx.event.EventType<MouseEvent> type,
                                      double x, double y, MouseButton button, boolean primaryDown) {
        boolean down = type != MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(type, x, y, x, y, button, 1,
                false, false, false, false, primaryDown,
                false, down && button == MouseButton.SECONDARY, false, false, true,
                new javafx.scene.input.PickResult(canvas, new javafx.geometry.Point3D(x, y, 0), 0));
    }

    @Test
    void loadedAreaListReopensCachedScenesAndTracksDimensionAndSourceChanges() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            MapViewerController controller = (MapViewerController) root.getProperties().get("mapViewerController");
            var worldField = MapViewerController.class.getDeclaredField("world");
            worldField.setAccessible(true);
            Path world = ((WorldInfo) worldField.get(controller)).getFolderPath();
            java.util.concurrent.atomic.AtomicInteger shown = new java.util.concurrent.atomic.AtomicInteger();
            com.mcworldexplorer.viewer.ViewerWindow window = new com.mcworldexplorer.viewer.ViewerWindow() {
                @Override public void show(com.mcworldexplorer.viewer.ViewerWindowContent content, Completion completion) {
                    if (completion.isCurrent()) { shown.incrementAndGet(); completion.onShown(); }
                }
                @Override public void close() { }
            };
            var coordinator = new com.mcworldexplorer.viewer.VoxelPreviewCoordinator(window,
                    com.mcworldexplorer.viewer.ViewerPerformanceProfile.ENHANCED);
            var coordinatorField = MapViewerController.class.getDeclaredField("viewerCoordinator");
            coordinatorField.setAccessible(true);
            coordinatorField.set(controller, coordinator);
            // Seed scenes only after the controller has installed the active plugin snapshot.
            var configure=MapViewerController.class.getDeclaredMethod("ensureViewerCoordinator");configure.setAccessible(true);configure.invoke(controller);
            var cacheField = coordinator.getClass().getDeclaredField("cache");
            cacheField.setAccessible(true);
            var cache = (com.mcworldexplorer.viewer.ViewerSceneCache) cacheField.get(coordinator);
            var scene = new com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot(0, 0, java.util.List.of(),
                    new com.mcworldexplorer.voxel.mesh.MeshBounds(0, 0, 0, 0, 0, 0), 0, 0, 0, 0, java.util.List.of());
            int[] pixels = new int[160 * 100];
            for (int y = 0; y < 100; y++) for (int x = 0; x < 160; x++)
                pixels[y * 160 + x] = x < 80 ? 0xff77a64d : 0xff4c8ac9;
            var thumbnail = new com.mcworldexplorer.viewer.ViewerAreaThumbnail(160, 100, pixels);
            for (var target : java.util.List.of(
                    com.mcworldexplorer.voxel.data.ChunkRectangle.centered(0, 0, 3, 3),
                    com.mcworldexplorer.voxel.data.ChunkRectangle.centered(0, 0, 16, 8))) {
                var key = new com.mcworldexplorer.viewer.ViewerAreaKey(world, "minecraft:overworld", target,
                        com.mcworldexplorer.viewer.VoxelPreviewCoordinator.RENDER_VERSION);
                cache.put(key, scene, com.mcworldexplorer.viewer.RegionSourceSignature.capture(key),
                        java.util.Optional.of(thumbnail), 1);
            }
            var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            double originalWidth = viewport.getWidth();
            ToggleButton areas = (ToggleButton) root.lookup("#viewerAreasButton");
            areas.fire();
            awaitFx(() -> areas.getText().endsWith("2"));
            root.applyCss(); root.layout();
            assertTrue(root.lookup("#viewerAreasHost").isVisible());
            assertEquals(originalWidth, viewport.getWidth(), "浮层不应挤压地图");
            assertEquals(2, root.lookupAll(".viewer-area-open").size());
            assertEquals(2, root.lookup("#viewerAreaList").lookupAll("ImageView").size());
            assertEquals(1, root.lookupAll(".viewer-area-open").stream().filter(node -> !node.isDisabled()).count());
            for (var node : root.lookupAll(".viewer-area-remove")) {
                Button cross = (Button) node;
                assertEquals("×", cross.getText());
                assertEquals("删除此已加载区域", cross.getAccessibleText());
                var crossBounds = cross.localToScene(cross.getBoundsInLocal());
                var cardBounds = cross.getParent().localToScene(cross.getParent().getBoundsInLocal());
                assertTrue(crossBounds.getMinY() < cardBounds.getMinY() + 36);
                assertTrue(crossBounds.getMaxX() > cardBounds.getMaxX() - 36);
            }
            var panelBounds = root.lookup("#viewerAreasHost").localToScene(root.lookup("#viewerAreasHost").getBoundsInLocal());
            var zoomBounds = root.lookup("#mapZoomControls").localToScene(root.lookup("#mapZoomControls").getBoundsInLocal());
            assertFalse(panelBounds.intersects(zoomBounds), "区域列表不能遮住地图缩放按钮");
            assertTrue(root.lookup("#viewerAreaListFooter").getBoundsInLocal().getHeight() >= 38,
                    "两行缓存说明不能被滚动列表挤成一行");
            Event.fireEvent(root.lookup("#viewerAreaListClose"), escapeKey());
            assertFalse(root.lookup("#viewerAreasHost").isVisible());
            areas.fire();
            root.applyCss(); root.layout();
            assertTrue(panelBounds.getMaxY() <= viewport.localToScene(viewport.getBoundsInLocal()).getMaxY());
            snapshotIfRequested(root, "loaded-area-list-900x600.png");
            Button open = (Button) root.lookupAll(".viewer-area-open").stream()
                    .filter(node -> !node.isDisabled()).findFirst().orElseThrow();
            open.fire();
            awaitFx(() -> shown.get() == 1 && ((Label) root.lookup("#viewerSelectionLabel")).getText().contains("已打开缓存"));
            assertTrue(((Label) root.lookup("#viewerSelectionLabel")).getText().contains("1 个区块"),
                    "缓存命中不能丢失部分加载状态");
            ((ToggleButton) root.lookup("#viewerModeButton")).fire();
            // The fake window has returned to the 2D UI; a second list action must use the same cached scene.
            ((Button) root.lookupAll(".viewer-area-open").stream().filter(node -> !node.isDisabled())
                    .findFirst().orElseThrow()).fire();
            awaitFx(() -> shown.get() == 2 && ((Label) root.lookup("#viewerSelectionLabel")).getText().contains("已打开缓存"));
            assertEquals(2, cache.size(), "重新打开不能制造重复条目");
            var dimensions = (ComboBox<?>) root.lookup("#dimensionComboBox");
            dimensions.getSelectionModel().select(1);
            awaitFx(() -> areas.getText().endsWith("0") && !((ToggleButton) root.lookup("#viewerModeButton")).isDisabled());
            assertTrue(root.lookupAll(".viewer-area-open").isEmpty());
            dimensions.getSelectionModel().select(0);
            awaitFx(() -> areas.getText().endsWith("2"));
            Files.write(world.resolve("region/r.0.0.mca"), new byte[8192]);
            ((Button) root.lookup("#viewerAreaListClose")).fire();
            areas.fire();
            awaitFx(() -> areas.getText().endsWith("0"));
            assertTrue(root.lookupAll(".viewer-area-open").isEmpty());
            assertEquals(0, cache.size());
            var key = new com.mcworldexplorer.viewer.ViewerAreaKey(world, "minecraft:overworld",
                    com.mcworldexplorer.voxel.data.ChunkRectangle.centered(0, 0, 3, 3),
                    com.mcworldexplorer.viewer.VoxelPreviewCoordinator.RENDER_VERSION);
            cache.put(key, scene, com.mcworldexplorer.viewer.RegionSourceSignature.capture(key),
                    java.util.Optional.of(thumbnail), 0);
            areas.fire(); areas.fire();
            awaitFx(() -> areas.getText().endsWith("1"));
            ((Button) root.lookup(".viewer-area-remove")).fire();
            assertTrue(root.lookupAll(".viewer-area-open").isEmpty());
            assertEquals(0, cache.size());
            assertTrue(areas.getText().endsWith("0"));
            assertTrue(Files.isRegularFile(world.resolve("region/r.0.0.mca")), "删除区域不能删除存档");
            @SuppressWarnings("unchecked")
            ComboBox<com.mcworldexplorer.viewer.ViewerPerformanceProfile> profiles =
                    (ComboBox<com.mcworldexplorer.viewer.ViewerPerformanceProfile>) root.lookup("#viewerProfileComboBox");
            profiles.setValue(com.mcworldexplorer.viewer.ViewerPerformanceProfile.ENHANCED);
            var second = new com.mcworldexplorer.viewer.ViewerAreaKey(world, "minecraft:overworld",
                    com.mcworldexplorer.voxel.data.ChunkRectangle.centered(4, 0, 3, 3),
                    com.mcworldexplorer.viewer.VoxelPreviewCoordinator.RENDER_VERSION);
            var otherDimension = new com.mcworldexplorer.viewer.ViewerAreaKey(world, "minecraft:the_nether",
                    key.target(), com.mcworldexplorer.viewer.VoxelPreviewCoordinator.RENDER_VERSION);
            for (var entry : java.util.List.of(key, second, otherDimension)) {
                cache.put(entry, scene, com.mcworldexplorer.viewer.RegionSourceSignature.capture(entry),
                        java.util.Optional.of(thumbnail), 0);
            }
            areas.fire(); areas.fire();
            awaitFx(() -> areas.getText().endsWith("2"));
            Button clear = (Button) root.lookup("#viewerAreaListClear");
            assertFalse(clear.isDisabled());
            clear.fire();
            assertTrue(root.lookupAll(".viewer-area-open").isEmpty());
            assertEquals(java.util.List.of(otherDimension), cache.keys(), "清空只作用于当前列表");
            assertTrue(clear.isDisabled());
        });
    }

    @Test
    void configuredRealWorldProducesAThumbnailAndReopensWithoutRebuilding() throws Exception {
        String configured = System.getenv("MCWORLD_UI_TEST_WORLD");
        assumeTrue(configured != null && !configured.isBlank());
        Path world = Path.of(configured).toRealPath();
        var before = snapshotWorld(world);
        try {
            withWorkspace(root -> {
                selectWorld(root, LevelDatReader.readLevelDat(world));
                awaitFx(() -> !((ToggleButton) root.lookup("#viewerModeButton")).isDisabled());
                MapViewerController controller = (MapViewerController) root.getProperties().get("mapViewerController");
                java.util.concurrent.atomic.AtomicInteger shown = new java.util.concurrent.atomic.AtomicInteger();
                com.mcworldexplorer.viewer.ViewerWindow window = new com.mcworldexplorer.viewer.ViewerWindow() {
                    @Override public void show(com.mcworldexplorer.viewer.ViewerWindowContent content, Completion completion) {
                        if (completion.isCurrent()) { shown.incrementAndGet(); completion.onShown(); }
                    }
                    @Override public void close() { }
                };
                var field = MapViewerController.class.getDeclaredField("viewerCoordinator");
                field.setAccessible(true);
                field.set(controller, new com.mcworldexplorer.viewer.VoxelPreviewCoordinator(window,
                        com.mcworldexplorer.viewer.ViewerPerformanceProfile.BASIC));
                var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                awaitFx(viewport::hasCompleteTargetCoverage);
                ((ToggleButton) root.lookup("#viewerModeButton")).fire();
                root.applyCss(); root.layout();
                // Select the loaded terrain around the origin through real Canvas events.
                viewport.centerOn(0, 0);
                double x = viewport.getWidth() / 2;
                double y = viewport.getHeight() / 2;
                var canvas = viewport.lookup("Canvas");
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, x, y, MouseButton.PRIMARY));
                Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, x, y, MouseButton.PRIMARY));
                ((Button) root.lookup("#viewerConfirmButton")).fire();
                awaitFx(() -> shown.get() == 1 && ((ToggleButton) root.lookup("#viewerAreasButton")).getText().endsWith("1"));
                var imageView = (javafx.scene.image.ImageView) root.lookup("#viewerAreaList").lookup("ImageView");
                assertNotNull(imageView);
                assertTrue(root.lookup("#viewerAreasHost").isVisible());
                ((ToggleButton) root.lookup("#viewerModeButton")).fire();
                root.applyCss(); root.layout();
                assertTrue(root.lookup(".viewer-area-scroll").getBoundsInLocal().getHeight() >= 90,
                        "条目不能因浮层首选高度循环而被压缩到零高度");
                snapshotIfRequested(root, "loaded-area-list-real-world.png");
                ((Button) root.lookup(".viewer-area-open")).fire();
                awaitFx(() -> shown.get() == 2 && ((Label) root.lookup("#viewerSelectionLabel")).getText().contains("已打开缓存"));
                awaitFx(() -> !root.lookup(".viewer-area-remove").isDisabled());
                ((Button) root.lookup(".viewer-area-remove")).fire();
                assertTrue(root.lookupAll(".viewer-area-open").isEmpty());
                System.out.println("Real world area list: 2D map thumbnail generated; cached reopen and deletion passed; window is a test double.");
            });
        } finally {
            assertEquals(before, snapshotWorld(world), "生成缩略图或缓存重开改变了源存档");
        }
    }

    @Test
    void searchesAndVisitsPlacesInsideLayerPopupWithoutClosingIt() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            var store = new SavedPlaces(home.resolve("empty-test-world"));
            store.change(new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, -7, 11), null, "基地 Alpha");
            store.change(new SavedPlaces.Position(WorldDimension.NETHER_ID, 28, 34), null, "基地 Beta");
            for (int i = 0; i < 48; i++) {
                store.change(new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, i, 200), null, "村庄 " + i);
            }
            Stage stage = new Stage();
            stage.setScene(root.getScene());
            stage.setOpacity(0);
            try {
                stage.show();
                var layers = (MenuButton) root.lookup("#layersMenuButton");
                var view = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                layers.show();
                Parent body = menuContent(layers);
                var panel = (SavedPlacesPanel) body.lookup("#savedPlacesPanel");
                assertFalse(panel.isExpanded());
                panel.setExpanded(true);
                body.getScene().getRoot().applyCss();
                body.getScene().getRoot().layout();
                var search = (TextField) panel.lookup("#placeSearch");
                var list = (javafx.scene.control.ListView<?>) panel.lookup("#placeResults");
                assertEquals(50, list.getItems().size());
                assertTrue(body.getBoundsInLocal().getWidth() < view.getWidth());
                double height = list.getHeight();
                assertTrue(height > 0 && height <= 116);
                list.scrollTo(49);
                list.layout();
                assertTrue(list.lookupAll(".list-cell").stream().anyMatch(n ->
                        n instanceof javafx.scene.control.ListCell<?> c && c.getIndex() == 49 && !c.isEmpty()));
                assertEquals(height, list.getHeight());
                search.setText("空结果");
                assertTrue(list.getItems().isEmpty());
                search.setText("  alPHA ");
                assertEquals(1, list.getItems().size());
                clickFirstPlaceRow(list);
                assertEquals(-6.5, view.viewportState().centerX());
                assertTrue(layers.isShowing() && panel.isExpanded());
                search.setText("下界");
                clickFirstPlaceRow(list);
                awaitFx(() -> view.viewportState() != null && view.viewportState().centerX() == 28.5);
                assertTrue(layers.isShowing() && panel.isExpanded());
                assertEquals("下界", search.getText());
                panel.setExpanded(false);
                assertTrue(layers.isShowing());
            } finally {
                stage.close();
            }
        });
    }

    private static void clickFirstPlaceRow(javafx.scene.control.ListView<?> list) {
        list.applyCss();
        list.layout();
        var cell = list.lookupAll(".list-cell").stream()
                .filter(n -> n instanceof javafx.scene.control.ListCell<?> c && c.getIndex() == 0 && !c.isEmpty())
                .findFirst().orElseThrow();
        Event.fireEvent(cell, pointer(cell, MouseEvent.MOUSE_PRESSED, 5, 5, MouseButton.PRIMARY));
        Event.fireEvent(cell, pointer(cell, MouseEvent.MOUSE_RELEASED, 5, 5, MouseButton.PRIMARY));
        Event.fireEvent(cell, pointer(cell, MouseEvent.MOUSE_CLICKED, 5, 5, MouseButton.PRIMARY));
    }

    @Test
    void popupControlsFitAndCoordinateErrorsDoNotCloseTheMenu() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setX(-10000);
            stage.setScene(root.getScene());
            try {
                stage.show();
                System.out.printf("JavaFX test window output scale: %.2f x %.2f%n",
                        stage.getOutputScaleX(), stage.getOutputScaleY());
                for (String id : new String[]{"locateMenuButton", "layersMenuButton", "moreMenuButton"}) {
                    MenuButton menu = (MenuButton) root.lookup("#" + id);
                    menu.show();
                    for (Window window : java.util.List.copyOf(Window.getWindows())) {
                        if (window instanceof PopupWindow) {
                            window.setOpacity(0);
                        }
                    }
                    Parent content = menuContent(menu);
                    content.getScene().getRoot().applyCss();
                    content.getScene().getRoot().layout();
                    assertTrue(menu.isShowing(), id + " 未展开");
                    assertTrue(content.getBoundsInLocal().getWidth() <= content.getScene().getWidth());
                    assertTrue(content.getBoundsInLocal().getHeight() <= content.getScene().getHeight());
                    if (id.equals("locateMenuButton")) {
                        TextField x = (TextField) content.lookup("#xField");
                        x.setText("invalid");
                        Event.fireEvent(x, new ActionEvent());
                        assertTrue(menu.isShowing(), "输入无效时保留可修正的定位弹窗");
                    }
                    if (id.equals("moreMenuButton")) {
                        MenuButton png = exportMenu(root);
                        Button clear = (Button) content.lookup("#clearCacheButton");
                        assertEquals(clear.getWidth(), png.getWidth(), 0.5);
                        assertEquals(clear.getHeight(), png.getHeight(), 0.5);
                        png.show();
                        assertTrue(png.isShowing());
                        assertTrue(menu.isShowing());
                        png.hide();
                    }
                    snapshotIfRequested(content.getScene().getRoot(), id + ".png");
                    Event.fireEvent(content, escapeKey());
                    assertFalse(menu.isShowing(), id + " 应支持 Esc 关闭");
                }
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void longFailureMessagesKeepMapAndCancelActionInsideMinimumWindow() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            ((ToggleButton) root.lookup("#viewerModeButton")).fire();
            String failure = "无法读取很长的存档路径，请检查文件访问权限。".repeat(60);
            Label selection = (Label) root.lookup("#viewerSelectionLabel");
            Label status = (Label) root.lookup("#statusLabel");
            selection.setText(failure);
            status.setText(failure);
            Button cancel = (Button) root.lookup("#viewerCancelButton");
            cancel.setManaged(true);
            cancel.setVisible(true);
            root.applyCss();
            root.layout();
            assertTrue(root.lookup("#viewportHost").getBoundsInLocal().getHeight() >= 160);
            for (var node : new javafx.scene.Node[]{selection, status, cancel}) {
                assertTrue(node.localToScene(node.getBoundsInLocal()).getMaxY() <= 601,
                        "长失败信息使操作或状态超出窗口：" + node.getId());
            }
            assertNotNull(selection.getTooltip());
            assertEquals(failure, selection.getTooltip().getText());
            assertEquals(failure, status.getTooltip().getText());
            snapshotIfRequested(root, "workspace-long-error-900x600.png");
        });
    }

    @Test
    void browsesAndExportsConfiguredRealWorldWithoutChangingSourceFiles() throws Exception {
        String configured = System.getenv("MCWORLD_UI_TEST_WORLD");
        assumeTrue(configured != null && !configured.isBlank());
        Path worldPath = Path.of(configured).toRealPath();
        Map<String, String> before = snapshotWorld(worldPath);
        WorldInfo world = LevelDatReader.readLevelDat(worldPath);
        assertTrue(world.isParsed());
        try {
            withWorkspace(worldPath, root -> {
                @SuppressWarnings("unchecked")
                TreeView<WorldTreeNode> tree = (TreeView<WorldTreeNode>) root.lookup("#worldTreeView");
                awaitFx(() -> ((Label) root.lookup("#scanStatusLabel")).getText().startsWith("已找到"));
                TreeItem<WorldTreeNode> item = tree.getRoot().getChildren().stream()
                        .flatMap(group -> group.getChildren().stream())
                        .filter(entry -> entry.getValue().getWorldInfo().getFolderPath()
                                .toAbsolutePath().normalize().equals(worldPath))
                        .findFirst().orElseThrow();
                long started = System.nanoTime();
                tree.getSelectionModel().select(item);
                MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost"))
                        .getChildren().getFirst();
                awaitFx(() -> viewport.loadedTileCount() > 0);
                long firstTile = (System.nanoTime() - started) / 1_000_000;
                awaitFx(viewport::hasCompleteTargetCoverage);
                long complete = (System.nanoTime() - started) / 1_000_000;
                assertTrue(((Label) root.lookup("#statusLabel")).getText().contains("已清晰加载"));
                MenuItem export = exportMenu(root).getItems().getFirst();
                assertFalse(export.isDisable());
                export.fire();
                awaitFx(() -> ((Label) root.lookup("#statusLabel")).getText().startsWith("已导出当前视口"));
                try (var files = Files.list(home.resolve("exports"))) {
                    Path exported = files.filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();
                    BufferedImage image = ImageIO.read(exported.toFile());
                    assertNotNull(image);
                    assertEquals((int) viewport.getWidth(), image.getWidth());
                    assertEquals((int) viewport.getHeight(), image.getHeight());
                }
                snapshotIfRequested(root, "workspace-real-900x600.png");
                long warmStarted = System.nanoTime();
                tree.getSelectionModel().clearSelection();
                tree.getSelectionModel().select(item);
                awaitFx(() -> viewport.loadedTileCount() > 0);
                long warmFirstTile = (System.nanoTime() - warmStarted) / 1_000_000;
                awaitFx(viewport::hasCompleteTargetCoverage);
                long warmComplete = (System.nanoTime() - warmStarted) / 1_000_000;
                int measuredMapWidth = (int) viewport.getWidth();
                int measuredMapHeight = (int) viewport.getHeight();
                ((ToggleButton) root.lookup("#viewerModeButton")).fire();
                @SuppressWarnings("unchecked")
                ComboBox<com.mcworldexplorer.viewer.ViewerPerformanceProfile> profiles =
                        (ComboBox<com.mcworldexplorer.viewer.ViewerPerformanceProfile>) root.lookup("#viewerProfileComboBox");
                profiles.setValue(com.mcworldexplorer.viewer.ViewerPerformanceProfile.ENHANCED);
                @SuppressWarnings("unchecked")
                ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption> ranges =
                        (ComboBox<com.mcworldexplorer.viewer.ViewerRangeOption>) root.lookup("#viewerRangeComboBox");
                ranges.setValue(com.mcworldexplorer.viewer.ViewerRangeOption.FREE);
                root.applyCss();
                root.layout();
                drag(viewport.lookup("Canvas"), 100, 80, 220, 136, MouseButton.PRIMARY);
                assertTrue(viewport.viewerLockedSelection().isPresent());
                awaitFx(() -> !((Button) root.lookup("#viewerConfirmButton")).isDisabled());
                snapshotIfRequested(root, "free-selection-real-world.png");
                System.out.printf("JavaFX real world %s: empty application cache first full tile=%dms, "
                                + "viewport complete=%dms; disk-cache reopen first full tile=%dms, "
                                + "viewport complete=%dms; map=%dx%d%n", world.getLevelName(),
                        firstTile, complete, warmFirstTile, warmComplete,
                        measuredMapWidth, measuredMapHeight);
            });
        } finally {
            assertEquals(before, snapshotWorld(worldPath), "UI 浏览或导出改变了原存档");
        }
    }

    private static Map<String, String> snapshotWorld(Path world) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var files = Files.walk(world)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var stream = new DigestInputStream(Files.newInputStream(file), digest)) {
                    stream.transferTo(java.io.OutputStream.nullOutputStream());
                }
                result.put(world.relativize(file).toString(), Files.size(file) + ":"
                        + Files.getLastModifiedTime(file).toMillis() + ":"
                        + HexFormat.of().formatHex(digest.digest()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void selectWorld(Parent root, WorldInfo world) {
        TreeView<WorldTreeNode> tree = (TreeView<WorldTreeNode>) root.lookup("#worldTreeView");
        TreeItem<WorldTreeNode> item = new TreeItem<>(WorldTreeNode.world(world));
        tree.getRoot().getChildren().add(item);
        tree.getSelectionModel().select(item);
        awaitFx(() -> {
            String text = ((MenuButton) root.lookup("#viewerTextureMenu")).getText();
            return !text.equals("纹理：查找中") && !text.equals("纹理：校验中");
        });
    }

    private void loadEmptyTestWorld(Parent root) throws Exception {
        loadEmptyTestWorld(root, null);
    }

    private void loadEmptyTestWorld(Parent root, String name) throws Exception {
        Path worldPath = Files.createDirectories(home.resolve("empty-test-world"));
        Files.createDirectories(worldPath.resolve("region"));
        Files.createDirectories(worldPath.resolve("DIM-1/region"));
        Files.createDirectories(worldPath.resolve("DIM1/region"));
        WorldInfo world = new WorldInfo(worldPath);
        if (name != null) world.setLevelName(name);
        world.setParsed(true);
        world.setVersionName("1.21.1");
        selectWorld(root, world);
        awaitFx(() -> !((ToggleButton) root.lookup("#viewerModeButton")).isDisabled());
        root.layout();
    }

    private static Parent menuContent(MenuButton menu) {
        return (Parent) menu.getItems().stream().filter(CustomMenuItem.class::isInstance)
                .map(CustomMenuItem.class::cast).filter(item -> item.getContent() instanceof javafx.scene.layout.VBox)
                .findFirst().orElseThrow().getContent();
    }

    private static MenuButton exportMenu(Parent root) {
        return (MenuButton) menuContent((MenuButton) root.lookup("#moreMenuButton")).lookup("#exportButton");
    }

    private Path rangeResult(Label status) {
        String prefix = "已导出指定范围地表\n";
        awaitFx(() -> status.getText().startsWith(prefix));
        Path relative = Path.of(status.getText().substring(prefix.length()));
        assertFalse(relative.isAbsolute());
        assertEquals(Path.of("exports"), relative.getParent());
        Path png = home.resolve(relative);
        assertTrue(Files.isRegularFile(png));
        return png;
    }

    private static java.util.List<Path> recordOpenedFolders(Parent root) throws Exception {
        var opened = new java.util.ArrayList<Path>();
        var field = MapViewerController.class.getDeclaredField("exportFolders");
        field.setAccessible(true);
        field.set(root.getProperties().get("mapViewerController"), new ExportFolders(opened::add));
        return opened;
    }

    @Test
    void viewportNoticeTracksActualFolderIndependentlyOfMapStatus() throws Exception {
        withWorkspace(root -> {
            var opened = recordOpenedFolders(root);
            var menu = exportMenu(root);
            assertFalse(menu.isDisabled());
            assertTrue(menu.getItems().getFirst().isDisable());
            menu.getItems().getLast().fire();
            assertEquals(List.of(home.resolve("exports")), opened);
            assertTrue(Files.isDirectory(opened.getFirst()));
            loadEmptyTestWorld(root);
            var controller = (MapViewerController) root.getProperties().get("mapViewerController");
            var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            var actual = Files.createDirectory(home.resolve("另存位置"));
            var file = new com.mcworldexplorer.map.ViewportExporter().exportToFile(
                    new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), actual.resolve("另存视口.png"),
                    home.resolve("empty-test-world"));
            viewport.requestFocus();
            var focusBefore = root.getScene().getFocusOwner();
            controller.showViewportExportSuccess(file);
            assertSame(focusBefore, root.getScene().getFocusOwner());
            viewport.centerOn(15, -30);
            var notice = root.lookup("#exportNotice");
            assertTrue(notice.isVisible());
            assertEquals(file.getFileName().toString(), ((Label) root.lookup("#exportNoticeFile")).getText());
            assertFalse(((Label) root.lookup("#statusLabel")).getText().startsWith("已导出当前视口"));
            ((Button) root.lookup("#exportNoticeFolder")).fire();
            menu.getItems().getLast().fire();
            assertEquals(List.of(home.resolve("exports"), actual, home.resolve("exports")), opened);
            var timerField = MapViewerController.class.getDeclaredField("exportNoticeTimeout");
            timerField.setAccessible(true);
            var timer = (PauseTransition) timerField.get(controller);
            assertEquals(Duration.seconds(8), timer.getDuration());
            Event.fireEvent(notice, mouse(MouseEvent.MOUSE_ENTERED, MouseButton.NONE));
            assertEquals(javafx.animation.Animation.Status.PAUSED, timer.getStatus());
            timer.jumpTo(Duration.millis(7900));
            Event.fireEvent(notice, mouse(MouseEvent.MOUSE_EXITED, MouseButton.NONE));
            awaitFx(() -> !notice.isVisible());
            assertFalse(notice.isManaged());
        });
    }

    @Test
    void showsCompleteLongChineseRangePathAndOpensFolder() throws Exception {
        checkRangeResultLayout("山间基地".repeat(20));
    }

    @Test
    void wrapsLongUnbrokenRangeFilename() throws Exception {
        checkRangeResultLayout("W".repeat(80));
    }

    private void checkRangeResultLayout(String name) throws Exception {
        withWorkspace(root -> {
            var opened = recordOpenedFolders(root);
            loadEmptyTestWorld(root, name);
            var stage = new Stage();
            stage.setScene(root.getScene());
            try {
                exportMenu(root).getItems().get(1).fire();
                DialogPane pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                        .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                var window = pane.getScene().getWindow();
                var folder = (Button) pane.lookup("#rangeExportFolder");
                folder.fire();
                assertTrue(window.isShowing());
                ((TextField) pane.lookup("#firstCorner")).setText("0, 0");
                ((TextField) pane.lookup("#secondCorner")).setText("2, 2");
                double initialHeight = window.getHeight();
                ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                var status = (Label) pane.lookup("#rangeExportStatus");
                var png = rangeResult(status);
                awaitFx(() -> window.getHeight() > initialHeight && status.getHeight() >= status.prefHeight(status.getWidth()) - 1);
                pane.applyCss();
                pane.layout();
                var text = (javafx.scene.text.Text) status.lookup(".text");
                assertEquals("已导出指定范围地表\n" + home.relativize(png), text.getText());
                var bounds = text.localToScene(text.getBoundsInLocal());
                var labelBounds = status.localToScene(status.getLayoutBounds());
                assertTrue(bounds.getMaxX() <= labelBounds.getMaxX() + 1);
                assertTrue(bounds.getMaxY() <= labelBounds.getMaxY() + 1);
                double buttonsY = folder.localToScene(folder.getBoundsInLocal()).getMinY();
                assertTrue(bounds.getMaxY() <= buttonsY, () -> "结果文字底部 " + bounds.getMaxY()
                        + "，按钮顶部 " + buttonsY + "，小窗 " + pane.getWidth() + " × " + pane.getHeight()
                        + "，首选高度 " + pane.prefHeight(pane.getWidth()) + "，初始窗口高度 " + initialHeight);
                assertTrue(pane.getWidth() <= 431, "长文件名应在小窗内换行");
                folder.fire();
                assertTrue(window.isShowing());
                assertEquals(List.of(home.resolve("exports"), png.getParent()), opened);
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void copiesBothCornersFromMapWhileRangeWindowStaysOpen() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Stage owner = new Stage();
            owner.setScene(root.getScene());
            owner.setOpacity(0);
            owner.show();
            var clipboard = Clipboard.getSystemClipboard();
            var previous = new ClipboardContent();
            for (var type : clipboard.getContentTypes()) {
                var value = clipboard.getContent(type);
                if (value != null) previous.put(type, value);
            }
            try {
                MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                viewport.centerOn(0, 0);
                var item = exportMenu(root).getItems().get(1);
                item.fire();
                DialogPane pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                        .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                Stage form = (Stage) pane.getScene().getWindow();
                assertEquals(javafx.stage.Modality.NONE, form.getModality());
                var first = (TextField) pane.lookup("#firstCorner");
                var second = (TextField) pane.lookup("#secondCorner");
                Button export = (Button) pane.lookupButton(pane.getButtonTypes().getFirst());
                assertTrue(BlockCoordinates.isPair(first.getText()));
                for (String invalid : List.of("-16", "1, 2, 3", "1.5, 0", "2147483648, 0")) {
                    first.setText(invalid);
                    assertTrue(export.isDisabled());
                }
                copyMapCorner(viewport, -16, -8, first);
                var canvas = viewport.lookup("Canvas");
                double center = viewport.viewportState().centerX();
                drag(canvas, 90, 80, 102, 80, MouseButton.SECONDARY);
                assertNotEquals(center, viewport.viewportState().centerX());
                item.fire();
                assertTrue(form.isShowing());
                assertEquals("-16, -8", first.getText());
                copyMapCorner(viewport, 15, 7, second);
                Label status = (Label) pane.lookup("#rangeExportStatus");
                assertEquals("输出尺寸：32 × 16 像素", status.getText());
                assertFalse(export.isDisabled());
                export.fire();
                var image = ImageIO.read(rangeResult(status).toFile());
                assertEquals(32, image.getWidth());
                assertEquals(16, image.getHeight());
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            } finally {
                String value = clipboard.getString();
                if ("-16, -8".equals(value) || "15, 7".equals(value)) clipboard.setContent(previous);
                owner.close();
            }
        });
    }

    private static void copyMapCorner(MapViewport viewport, int x, int z, TextField field) {
        Window mapWindow = viewport.getScene().getWindow();
        mapWindow.requestFocus();
        awaitFx(mapWindow::isFocused);
        double sx = viewport.viewportState().screenXFor(x + 0.5, viewport.getWidth());
        double sy = viewport.viewportState().screenYFor(z + 0.5, viewport.getHeight());
        drag(viewport.lookup("Canvas"), sx, sy, sx, sy, MouseButton.SECONDARY);
        var menu = showingPlaceMenu();
        var copy = menu.getItems().stream().filter(i -> "复制坐标".equals(i.getText())).findFirst().orElseThrow();
        copy.fire();
        menu.hide();
        String expected = x + ", " + z;
        awaitFx(() -> expected.equals(Clipboard.getSystemClipboard().getString()));
        assertEquals(expected, Clipboard.getSystemClipboard().getString(),
                () -> ((Label) viewport.getScene().lookup("#statusLabel")).getText());
        Window formWindow = field.getScene().getWindow();
        formWindow.requestFocus();
        awaitFx(formWindow::isFocused);
        field.requestFocus();
        field.positionCaret(1);
        field.paste();
        assertEquals(expected, field.getText());
    }

    @Test
    void wheelRoundTripKeepsIntegralTileSizeAndPixels() throws Exception {
        withWorkspace(root -> {
            var zoom = com.mcworldexplorer.map.MapZoomLevel.BLOCKS_1;
            var key = new com.mcworldexplorer.map.MapTileKey("world", "minecraft:overworld",
                    com.mcworldexplorer.preview.PreviewLayer.surfaceOverview(), zoom, 0, 0, "test");
            var view = new MapViewport();
            view.resize(512, 512);
            view.layout();
            var state = new com.mcworldexplorer.map.MapViewportState(128, 128, zoom);
            view.setViewportState(state);
            view.setTargetTiles(List.of(key));
            var stripes = new WritableImage(256, 256);
            var writer = stripes.getPixelWriter();
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
                writer.setArgb(x, y, (x % 2 == 0) ? 0xff447755 : 0xffaacc77);
            view.showTile(key, stripes, List.of());
            int[] exact = view.snapshotTerrain().getRGB(0, 0, 512, 512, null, 0, 512);
            state.zoomVisualAt(0.9999999999999964, 256, 256, 512, 512);
            assertArrayEquals(exact, view.snapshotTerrain().getRGB(0, 0, 512, 512, null, 0, 512));
        });
    }

    @Test
    void fractionalZoomAssignsEachEdgePixelToTheSameTileInEitherLoadOrder() throws Exception {
        withWorkspace(root -> {
            var zoom = com.mcworldexplorer.map.MapZoomLevel.BLOCKS_1;
            var layer = com.mcworldexplorer.preview.PreviewLayer.surfaceOverview();
            var keys = new java.util.ArrayList<com.mcworldexplorer.map.MapTileKey>();
            for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++) {
                keys.add(new com.mcworldexplorer.map.MapTileKey("world", "minecraft:overworld", layer, zoom, x, z, "test"));
            }
            for (var order : List.of(keys, keys.reversed())) {
                var view = new MapViewport();
                view.resize(512, 512);
                view.layout();
                var state = new com.mcworldexplorer.map.MapViewportState(.25, .25, zoom);
                state.zoomVisualAt(Math.nextDown(1.0), 256, 256, 512, 512);
                view.setViewportState(state);
                view.setTargetTiles(keys);
                for (var key : order) {
                    var image = new WritableImage(1, 1);
                    image.getPixelWriter().setArgb(0, 0, 0xff344450 + keys.indexOf(key));
                    view.showTile(key, image, List.of());
                }
                var screen = view.snapshotTerrain();
                for (int i = 0; i < keys.size(); i++) {
                    assertEquals(0xff344450 + i, screen.getRGB(254 + i % 2, 254 + i / 2));
                }
            }
        });
    }

    @Test
    void bothPngModesProduceIdenticalTerrainWhileOverlaysStayVisible() throws Exception {
        Path region = home.resolve("empty-test-world/region/r.0.0.mca");
        com.mcworldexplorer.map.MapSamplingFixtures.region(region, "minecraft:stone", 0, 1, 15, 16);
        withWorkspace(root -> {
            var store = new SavedPlaces(home.resolve("empty-test-world"));
            var first = new SavedPlaces.Position(WorldDimension.OVERWORLD_ID, 250, 8);
            store.change(first, null, "接缝旁的地点");
            store.connect(first, new SavedPlaces.Position(first.dimension(), 220, 8), "西端");
            loadEmptyTestWorld(root);
            var stage = new Stage();
            stage.setScene(root.getScene());
            try {
                var viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
                var zoom = com.mcworldexplorer.map.MapZoomLevel.BLOCKS_1;
                double cx = viewport.getWidth() / 2, cz = viewport.getHeight() / 2;
                viewport.viewportState().setView(cx, cz, zoom);
                viewport.centerOn(cx, cz);
                var menu = exportMenu(root);
                awaitFx(() -> !menu.getItems().getFirst().isDisable() && viewport.hasCompleteTargetCoverage()
                        && viewport.completeTiles().keySet().stream().anyMatch(key -> key.zoom() == zoom));
                viewport.setViewerSelectionMode(true);
                viewport.lockViewerSelection(new com.mcworldexplorer.voxel.data.ChunkRectangle(13, 1, 13, 1));
                var canvas = viewport.lookup("Canvas");
                var screenBefore = canvas.snapshot(null, null);
                menu.getItems().getFirst().fire();
                var screenAfter = canvas.snapshot(null, null);
                var mapStatus = (Label) root.lookup("#statusLabel");
                awaitFx(() -> mapStatus.getText().startsWith("已导出当前视口："));
                BufferedImage current = ImageIO.read(Path.of(mapStatus.getText().substring("已导出当前视口：".length())).toFile());

                menu.getItems().get(1).fire();
                DialogPane pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                        .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                Label status = (Label) pane.lookup("#rangeExportStatus");
                BufferedImage range = ImageIO.read(rangeResult(status).toFile());
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();

                int w = current.getWidth(), h = current.getHeight();
                assertEquals((int) screenBefore.getWidth(), w);
                assertEquals((int) screenBefore.getHeight(), h);
                assertEquals(w, range.getWidth());
                assertEquals(h, range.getHeight());
                assertNotEquals(current.getRGB(245, 8), current.getRGB(100, 8), "保留已保存地形和空白区域");
                assertArrayEquals(range.getRGB(0, 0, w, h, null, 0, w), current.getRGB(0, 0, w, h, null, 0, w));
                for (int[] pixel : List.of(new int[]{235, 8}, new int[]{250, 8}, new int[]{256, 8}, new int[]{216, 24})) {
                    int x = pixel[0], y = pixel[1];
                    int displayed = screenBefore.getPixelReader().getArgb(x, y);
                    assertNotEquals(displayed, current.getRGB(x, y), "连线、图标、瓦片线框和选区仅在界面显示");
                    assertEquals(displayed, screenAfter.getPixelReader().getArgb(x, y));
                }
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void exportsOffscreenRangeFromSharedPngMenu() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Files.write(home.resolve("empty-test-world/region/r.20.20.mca"), new byte[12]);
            MapViewport viewport = (MapViewport) ((StackPane) root.lookup("#viewportHost")).getChildren().getFirst();
            viewport.centerOn(10250, 10250);
            Label mapStatus = (Label) root.lookup("#statusLabel");
            awaitFx(() -> mapStatus.getText().contains("部分区域未完整加载"));
            MenuButton menu = exportMenu(root);
            assertFalse(menu.isDisable());
            assertTrue(menu.getItems().getFirst().isDisable());
            assertInstanceOf(javafx.scene.control.SeparatorMenuItem.class, menu.getItems().get(2));
            assertEquals(List.of("导出当前视口", "导出指定范围地表…", "打开导出文件夹"),
                    menu.getItems().stream().filter(i -> !(i instanceof javafx.scene.control.SeparatorMenuItem))
                            .map(MenuItem::getText).toList());
            Stage stage = new Stage();
            stage.setScene(root.getScene());
            try {
                menu.getItems().get(1).fire();
                DialogPane pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                        .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                Button export = (Button) pane.lookupButton(pane.getButtonTypes().getFirst());
                ((TextField) pane.lookup("#firstCorner")).setText("-10000, 7");
                ((TextField) pane.lookup("#secondCorner")).setText("0, -8");
                assertTrue(export.isDisabled());
                ((TextField) pane.lookup("#secondCorner")).setText("-9969, -8");
                assertFalse(export.isDisabled());
                Label status = (Label) pane.lookup("#rangeExportStatus");
                assertTrue(status.getText().contains("32 × 16"));
                export.fire();
                awaitFx(() -> status.getText().startsWith("已导出指定范围地表"));
                try (var files = Files.list(home.resolve("exports"))) {
                    var png = ImageIO.read(files.findFirst().orElseThrow().toFile());
                    assertEquals(32, png.getWidth());
                    assertEquals(16, png.getHeight());
                }
                Window window = pane.getScene().getWindow();
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                assertFalse(window.isShowing());
                viewport.centerOn(0, 0);
                MenuItem viewportExport = menu.getItems().getFirst();
                awaitFx(() -> !viewportExport.isDisable());
                viewportExport.fire();
                awaitFx(() -> ((Label) root.lookup("#statusLabel")).getText().startsWith("已导出当前视口"));
                assertTrue(root.lookup("#exportNotice").isVisible());
                String fileName = ((Label) root.lookup("#exportNoticeFile")).getText();
                assertTrue(Files.isRegularFile(home.resolve("exports").resolve(fileName)));
                try (var files = Files.list(home.resolve("exports"))) {
                    assertEquals(2, files.count());
                }
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void cancelsRangeAndReportsUnreadableOffscreenRegionWithoutWritingPng() throws Exception {
        withWorkspace(root -> {
            loadEmptyTestWorld(root);
            Files.write(home.resolve("empty-test-world/region/r.20.20.mca"), new byte[12]);
            var stage = new Stage();
            stage.setScene(root.getScene());
            try {
                MenuItem item = exportMenu(root).getItems().get(1);
                for (boolean cancel : List.of(true, false)) {
                    item.fire();
                    DialogPane pane = Window.getWindows().stream().map(w -> w.getScene().getRoot())
                            .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                    for (String id : List.of("firstCorner", "secondCorner")) {
                        ((TextField) pane.lookup("#" + id)).setText(cancel ? "0, 0" : "10250, 10250");
                    }
                    ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
                    if (!cancel) {
                        Label status = (Label) pane.lookup("#rangeExportStatus");
                        awaitFx(() -> status.getText().contains("r.20.20.mca"));
                        assertTrue(status.getText().startsWith("导出失败"));
                    }
                    ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                }
                assertTrue(Files.notExists(home.resolve("exports")));
                assertTrue(Files.notExists(home.resolve("cache/export-temp")));
            } finally {
                stage.close();
            }
        });
    }

    private static KeyEvent escapeKey() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, MouseButton button) {
        return new MouseEvent(type, 160, 100, 160, 100, button, 1,
                false, false, false, false, type == MouseEvent.MOUSE_PRESSED,
                false, false, false, false, true, null);
    }

    private static void awaitFx(BooleanSupplier ready) {
        if (ready.getAsBoolean()) {
            return;
        }
        Object key = new Object();
        Timeline poll = new Timeline();
        PauseTransition timeout = new PauseTransition(Duration.seconds(8));
        java.util.function.Consumer<Boolean> finish = result -> {
            poll.stop();
            timeout.stop();
            Platform.exitNestedEventLoop(key, result);
        };
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(25), e -> {
            if (ready.getAsBoolean()) finish.accept(true);
        }));
        poll.setCycleCount(Timeline.INDEFINITE);
        timeout.setOnFinished(e -> finish.accept(false));
        poll.play();
        timeout.play();
        try {
            assertEquals(true, Platform.enterNestedEventLoop(key), "后台地图初始化未按时完成");
        } finally {
            poll.stop();
            timeout.stop();
        }
    }








    private void withWorkspace(WorkspaceCheck check) throws Exception {
        withWorkspace(home.resolve("missing-minecraft-directory"), check);
    }

    private void withWorkspace(Path scanPath, WorkspaceCheck check) throws Exception {
        String previousHome = System.getProperty("mcworldexplorer.home");
        var closingScheduler = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ExecutorService>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            MainController controller = null;
            try {
                System.setProperty("mcworldexplorer.home", home.toString());
                new PortableSettings().saveCustomSavesPath(scanPath);
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"),
                        ResourceBundle.getBundle("messages"));
                Parent root = loader.load();
                controller = loader.getController();
                root.getProperties().put("mapViewerController", loader.getNamespace().get("mapViewerController"));
                MapViewerController mapController = (MapViewerController) root.getProperties().get("mapViewerController");
                var resourceRoots = MapViewerController.class.getDeclaredField("clientResourceRoots");
                resourceRoots.setAccessible(true);
                resourceRoots.set(mapController, (java.util.function.Supplier<java.util.List<Path>>) () -> java.util.List.of(home));
                var schedulerField = MapViewerController.class.getDeclaredField("scheduler");
                schedulerField.setAccessible(true);
                var executorField = com.mcworldexplorer.map.MapTileScheduler.class.getDeclaredField("executor");
                executorField.setAccessible(true);
                closingScheduler.set((java.util.concurrent.ExecutorService) executorField.get(schedulerField.get(mapController)));
                new Scene(root, 900, 600);
                root.applyCss();
                root.resize(900, 600);
                root.layout();
                check.run(root);
            } finally {
                if (controller != null) {
                    controller.shutdown();
                }
            }
            return null;
        });
        Platform.runLater(task);
        try { task.get(60, TimeUnit.SECONDS); }
        finally {
            try {
                var executor = closingScheduler.get();
                if (executor != null) assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "地图缓存写入线程尚未停止");
            } finally {
                if (previousHome == null) System.clearProperty("mcworldexplorer.home");
                else System.setProperty("mcworldexplorer.home", previousHome);
            }
        }
    }

    private static void snapshotIfRequested(Parent root, String name) throws Exception {
        String directory = System.getenv("MCWE_UI_SNAPSHOT_DIR");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path target = Path.of(directory);
        Files.createDirectories(target);
        WritableImage image = root.snapshot(null, null);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        ImageIO.write(output, "png", target.resolve(name).toFile());
    }

    private interface WorkspaceCheck {
        void run(Parent root) throws Exception;
    }
}
