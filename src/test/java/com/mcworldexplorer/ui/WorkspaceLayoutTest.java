package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.PortableSettings;
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
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
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
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses isolated configuration; real-save access is opt-in through MCWORLD_UI_TEST_WORLD. */
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
                var lookup=MapViewerController.class.getDeclaredMethod("startViewerResourceLookup",Path.class,boolean.class);
                lookup.setAccessible(true);lookup.invoke(controller,Path.of(configuredJar),false);
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

    @Test
    void textureMenuFollowsExistingViewerToolbarAndKeepsSimpleMode() throws Exception {
        withWorkspace(root -> {
            // This is a toolbar layout test; do not start unrelated asynchronous map writes.
            root.lookup("#viewerSelectionBar").setManaged(true);
            root.lookup("#viewerSelectionBar").setVisible(true);
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
            assertEquals(3, menu.getItems().size());
            assertEquals("手动选择 JAR", menu.getItems().getFirst().getText());
            assertNotNull(menu.getItems().getFirst().getOnAction());
            menu.getItems().getLast().fire();
            assertEquals("纹理：简化颜色", menu.getText());
            root.applyCss(); root.layout();
            var bounds = menu.localToScene(menu.getBoundsInLocal());
            assertTrue(bounds.getMaxX() <= 901);
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
            assertTrue(confirm.isDisabled());
            assertTrue(feedback.getText().contains("超出上限"));
            drag(canvas, 160, 100, 160, 100, MouseButton.PRIMARY);
            assertEquals(1, viewport.viewerLockedSelection().orElseThrow().width());
            assertFalse(confirm.isDisabled());
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

    private static void drag(javafx.scene.Node canvas, double x1, double y1,
                             double x2, double y2, MouseButton button) {
        Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_PRESSED, x1, y1, button));
        Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_DRAGGED, x2, y2, button));
        Event.fireEvent(canvas, pointer(canvas, MouseEvent.MOUSE_RELEASED, x2, y2, button));
    }

    private static MouseEvent pointer(javafx.scene.Node canvas, javafx.event.EventType<MouseEvent> type,
                                      double x, double y, MouseButton button) {
        boolean down = type != MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(type, x, y, x, y, button, 1,
                false, false, false, false, down && button == MouseButton.PRIMARY,
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
                Parent maintenance = menuContent((MenuButton) root.lookup("#moreMenuButton"));
                Button export = (Button) maintenance.lookup("#exportButton");
                assertFalse(export.isDisabled());
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
        Path worldPath = Files.createDirectories(home.resolve("empty-test-world"));
        Files.createDirectories(worldPath.resolve("region"));
        Files.createDirectories(worldPath.resolve("DIM-1/region"));
        Files.createDirectories(worldPath.resolve("DIM1/region"));
        WorldInfo world = new WorldInfo(worldPath);
        world.setParsed(true);
        world.setVersionName("1.21.1");
        selectWorld(root, world);
        awaitFx(() -> !((ToggleButton) root.lookup("#viewerModeButton")).isDisabled());
        root.layout();
    }

    private static Parent menuContent(MenuButton menu) {
        return (Parent) ((CustomMenuItem) menu.getItems().getFirst()).getContent();
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
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(25), e -> {
            if (ready.getAsBoolean()) {
                Platform.exitNestedEventLoop(key, true);
            }
        }));
        poll.setCycleCount(Timeline.INDEFINITE);
        PauseTransition timeout = new PauseTransition(Duration.seconds(8));
        timeout.setOnFinished(e -> Platform.exitNestedEventLoop(key, false));
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
                Scene scene = new Scene(root, 900, 600);
                scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
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
