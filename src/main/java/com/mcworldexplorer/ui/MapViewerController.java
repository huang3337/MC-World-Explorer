package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerType;
import com.mcworldexplorer.map.MapTileCacheResult;
import com.mcworldexplorer.map.MapTileGenerationMonitor;
import com.mcworldexplorer.map.MapTilePartialResult;
import com.mcworldexplorer.map.MapTileKey;
import com.mcworldexplorer.map.MapTileScheduler;
import com.mcworldexplorer.map.MapTileService;
import com.mcworldexplorer.map.MapViewportState;
import com.mcworldexplorer.map.MapZoomLevel;
import com.mcworldexplorer.map.ViewportCoordinator;
import com.mcworldexplorer.map.ViewportExporter;
import com.mcworldexplorer.map.WorldMapCacheCleaner;
import com.mcworldexplorer.nbt.PlayerDataReader;
import com.mcworldexplorer.preview.DimensionHeightRange;
import com.mcworldexplorer.preview.DimensionHeightResolver;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.PreviewRequest;
import com.mcworldexplorer.preview.PreviewRequestResolver;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.world.PlayerLocation;
import com.mcworldexplorer.world.WorldInfo;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MapViewerController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapViewerController.class);
    private static final String RENDER_VERSION = "v03-r1";
    private static final MapZoomLevel DEFAULT_ZOOM = MapZoomLevel.BLOCKS_2;

    private final MapTileService tileService = new MapTileService();
    private final MapTileScheduler scheduler = new MapTileScheduler();
    private final ViewportCoordinator coordinator = new ViewportCoordinator();
    private final WorldMapCacheCleaner cacheCleaner = new WorldMapCacheCleaner();
    private final ViewportExporter exporter = new ViewportExporter();
    private final PlayerDataReader playerDataReader = new PlayerDataReader();
    private final PauseTransition viewportDebounce = new PauseTransition(Duration.millis(200));
    private final Map<String, DimensionState> dimensionStates = new HashMap<>();
    private final Set<MapTileKey> readyKeys = new HashSet<>();
    private WorldInfo world;
    private WorldDimension dimension;
    private PreviewLayer layer;
    private List<MapTileKey> requestedKeys = List.of();
    private List<MapTileKey> prefetchKeys = List.of();
    private List<PlayerLocation> playerLocations = List.of();
    private PlayerNavigation pendingPlayerNavigation;
    private boolean updatingControls;
    private long contextId;
    private long playerLoadId;
    private int failedTiles;
    private long prefetchRequestId = -1;

    @FXML
    private ComboBox<WorldDimension> dimensionComboBox;
    @FXML
    private ToggleButton surfaceOverviewButton;
    @FXML
    private Slider layerHeightSlider;
    @FXML
    private Label layerRangeLabel;
    @FXML
    private TextField xField;
    @FXML
    private TextField zField;
    @FXML
    private Label coordinateErrorLabel;
    @FXML
    private ToggleButton playerMarkerButton;
    @FXML
    private MenuButton playerListButton;
    @FXML
    private ToggleButton spawnMarkerButton;
    @FXML
    private ToggleButton portalMarkerButton;
    @FXML
    private Button exportButton;
    @FXML
    private Button clearCacheButton;
    @FXML
    private Label statusLabel;
    @FXML
    private StackPane viewportHost;

    private MapViewport viewport;

    @FXML
    public void initialize() {
        viewport = new MapViewport();
        viewportHost.getChildren().setAll(viewport);
        viewport.setOnViewportChanged(() -> {
            updateStatus();
            viewportDebounce.playFromStart();
        });
        viewport.setOnVisualChanged(this::updateStatus);
        viewport.setOnZoomTargetChanged(() -> {
            updateStatus();
            refreshTiles();
        });
        viewportDebounce.setOnFinished(event -> refreshTiles());
        playerMarkerButton.selectedProperty().bindBidirectional(viewport.showPlayerProperty());
        spawnMarkerButton.selectedProperty().bindBidirectional(viewport.showSpawnProperty());
        portalMarkerButton.selectedProperty().bindBidirectional(viewport.showPortalsProperty());
        configureMarkerToggle(playerMarkerButton, MapMarkerType.PLAYER);
        configureMarkerToggle(spawnMarkerButton, MapMarkerType.WORLD_SPAWN);
        configureMarkerToggle(portalMarkerButton, MapMarkerType.NETHER_PORTAL);
        viewport.widthProperty().addListener((observable, oldValue, newValue) ->
                viewportDebounce.playFromStart());
        viewport.heightProperty().addListener((observable, oldValue, newValue) ->
                viewportDebounce.playFromStart());
        dimensionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingControls && world != null && newValue != null) {
                selectDimension(newValue);
            }
        });
        layerHeightSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingControls) {
                updateSliderLabel(newValue.doubleValue());
                if (!layerHeightSlider.isValueChanging()) {
                    selectSliderLayer();
                }
            }
        });
        layerHeightSlider.valueChangingProperty().addListener((observable, oldValue, changing) -> {
            if (!updatingControls && oldValue && !changing) {
                selectSliderLayer();
            }
        });
        clear();
    }

    public void setWorld(WorldInfo world) {
        clear();
        this.world = world;
        if (world == null || !world.isParsed()) {
            statusLabel.setText("该存档无法生成地图");
            return;
        }
        try {
            List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(world);
            updatingControls = true;
            dimensionComboBox.setItems(FXCollections.observableArrayList(dimensions));
            dimensionComboBox.getSelectionModel().selectFirst();
            dimensionComboBox.setDisable(dimensions.isEmpty());
            updatingControls = false;
            if (dimensionComboBox.getValue() == null) {
                statusLabel.setText("该存档没有可浏览维度");
            } else {
                selectDimension(dimensionComboBox.getValue());
            }
            refreshPlayerMenu();
            loadPlayers(world, playerLoadId);
        } catch (IOException e) {
            LOGGER.error("Failed to discover dimensions for {}", world.getFolderPath(), e);
            statusLabel.setText("维度读取失败：" + shortMessage(e));
        }
    }

    public void clear() {
        contextId++;
        playerLoadId++;
        viewportDebounce.stop();
        scheduler.cancelAll();
        tileService.clearMemory();
        readyKeys.clear();
        requestedKeys = List.of();
        prefetchKeys = List.of();
        playerLocations = List.of();
        pendingPlayerNavigation = null;
        dimensionStates.clear();
        world = null;
        dimension = null;
        layer = null;
        failedTiles = 0;
        if (viewport != null) {
            viewport.setViewportState(null);
            viewport.setFixedMarkers(List.of());
        }
        if (dimensionComboBox != null) {
            updatingControls = true;
            dimensionComboBox.getItems().clear();
            dimensionComboBox.setDisable(true);
            surfaceOverviewButton.setSelected(false);
            surfaceOverviewButton.setDisable(true);
            layerHeightSlider.setDisable(true);
            layerRangeLabel.setText("-");
            updatingControls = false;
            exportButton.setDisable(true);
            clearCacheButton.setDisable(true);
            coordinateErrorLabel.setText("");
            playerListButton.getItems().clear();
            playerListButton.setText("玩家列表");
            playerListButton.setDisable(true);
            statusLabel.setText("选择存档后浏览地图");
        }
    }

    private void selectDimension(WorldDimension selected) {
        if (pendingPlayerNavigation != null
                && !pendingPlayerNavigation.dimensionId().equals(selected.id())) {
            pendingPlayerNavigation = null;
        }
        contextId++;
        scheduler.cancelAll();
        readyKeys.clear();
        failedTiles = 0;
        dimension = selected;
        clearCacheButton.setDisable(false);
        statusLabel.setText("正在读取维度高度...");
        setLayerControlsDisabled(true);
        DimensionState existing = dimensionStates.get(selected.id());
        if (existing != null) {
            activateDimensionState(existing);
            return;
        }

        long taskContext = contextId;
        Task<DimensionState> task = new Task<>() {
            @Override
            protected DimensionState call() throws IOException {
                DimensionHeightRange range = DimensionHeightResolver.resolve(selected);
                PreviewRequest request = initialMapRequest(world, selected, range);
                int sliderY = defaultSliderY(range, request.layer());
                return new DimensionState(
                        range,
                        sliderY,
                        request.layer(),
                         new MapViewportState(
                                 request.center().x(),
                                 request.center().z(),
                                 DEFAULT_ZOOM));
            }
        };
        task.setOnSucceeded(event -> {
            if (taskContext != contextId) {
                return;
            }
            DimensionState state = task.getValue();
            dimensionStates.put(selected.id(), state);
            activateDimensionState(state);
        });
        task.setOnFailed(event -> {
            if (taskContext == contextId) {
                LOGGER.error("Failed to resolve map dimension {}", selected.id(), task.getException());
                statusLabel.setText("维度高度读取失败：" + shortMessage(task.getException()));
            }
        });
        Thread thread = new Thread(task, "map-height-resolver");
        thread.setDaemon(true);
        thread.start();
    }

    private void activateDimensionState(DimensionState state) {
        PlayerNavigation navigation = pendingPlayerNavigation;
        if (navigation != null && navigation.dimensionId().equals(dimension.id())) {
            state = state.withLayer(navigation.layer());
            state.viewportState().setView(
                    navigation.x(),
                    navigation.z(),
                    navigation.zoom());
            dimensionStates.put(dimension.id(), state);
            pendingPlayerNavigation = null;
        }
        layer = state.layer();
        updatingControls = true;
        surfaceOverviewButton.setSelected(layer.isSurfaceOverview());
        surfaceOverviewButton.setDisable(false);
        layerHeightSlider.setMin(state.range().minY());
        layerHeightSlider.setMax(state.range().maxY());
        layerHeightSlider.setValue(state.sliderY());
        layerHeightSlider.setDisable(false);
        layerRangeLabel.setText(MainController.formatLayerSliderLabel(state.range(), state.sliderY()));
        updatingControls = false;
        viewport.setViewportState(state.viewportState());
        viewport.setFixedMarkers(fixedMarkers());
        refreshTiles();
    }

    @FXML
    private void handleSurfaceOverview(ActionEvent event) {
        if (updatingControls || dimension == null) {
            return;
        }
        updatingControls = true;
        surfaceOverviewButton.setSelected(true);
        updatingControls = false;
        changeLayer(PreviewLayer.surfaceOverview());
    }

    private void selectSliderLayer() {
        if (dimension == null) {
            return;
        }
        DimensionState state = dimensionStates.get(dimension.id());
        if (state == null) {
            return;
        }
        int sliderY = MainController.sliderCoordinate(state.range(), layerHeightSlider.getValue());
        dimensionStates.put(dimension.id(), state.withSliderY(sliderY));
        updatingControls = true;
        surfaceOverviewButton.setSelected(false);
        updatingControls = false;
        changeLayer(state.range().bandContaining(sliderY));
    }

    private void changeLayer(PreviewLayer nextLayer) {
        if (nextLayer.equals(layer)) {
            return;
        }
        layer = nextLayer;
        DimensionState state = dimensionStates.get(dimension.id());
        dimensionStates.put(dimension.id(), state.withLayer(nextLayer));
        contextId++;
        scheduler.cancelAll();
        readyKeys.clear();
        viewport.clearTiles();
        refreshTiles();
    }

    private void updateSliderLabel(double value) {
        if (dimension == null) {
            return;
        }
        DimensionState state = dimensionStates.get(dimension.id());
        if (state != null) {
            layerRangeLabel.setText(MainController.formatLayerSliderLabel(state.range(), value));
        }
    }

    @FXML
    private void handleLocate(ActionEvent event) {
        try {
            int x = Integer.parseInt(xField.getText().strip());
            int z = Integer.parseInt(zField.getText().strip());
            coordinateErrorLabel.setText("");
            viewport.centerOn(x, z);
        } catch (NumberFormatException e) {
            coordinateErrorLabel.setText("X、Z 必须是整数");
        }
    }

    @FXML
    private void handleZoomIn(ActionEvent event) {
        viewport.zoomIn();
    }

    @FXML
    private void handleZoomOut(ActionEvent event) {
        viewport.zoomOut();
    }

    @FXML
    private void handleReset(ActionEvent event) {
        viewport.resetView();
    }

    @FXML
    private void handleClearCache(ActionEvent event) {
        if (world == null) {
            return;
        }
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "将删除“" + world.getLevelName()
                        + "”的静态预览和全部地图块缓存，不影响存档与其他世界。",
                ButtonType.OK,
                ButtonType.CANCEL);
        alert.initOwner(viewportHost.getScene().getWindow());
        alert.setTitle("清理当前世界缓存");
        alert.setHeaderText("确认清理当前世界缓存？");
        if (alert.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }

        WorldInfo target = world;
        clearCacheButton.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                cacheCleaner.clear(target);
                return null;
            }
        };
        task.setOnSucceeded(done -> {
            if (world == target) {
                tileService.clearMemory();
                readyKeys.clear();
                viewport.clearTiles();
                clearCacheButton.setDisable(false);
                statusLabel.setText("当前世界缓存已清理，正在重新加载");
                refreshTiles();
            }
        });
        task.setOnFailed(failed -> {
            LOGGER.error("Failed to clear cache for {}", target.getFolderPath(), task.getException());
            if (world == target) {
                clearCacheButton.setDisable(false);
                statusLabel.setText("缓存清理失败：" + shortMessage(task.getException()));
            }
        });
        Thread thread = new Thread(task, "map-cache-cleaner");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void handleExport(ActionEvent event) {
        if (world == null || exportButton.isDisabled()) {
            return;
        }
        BufferedImage snapshot = viewport.snapshotImage();
        WorldInfo targetWorld = world;
        exportButton.setDisable(true);
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws IOException {
                return exporter.exportToDefault(
                        snapshot,
                        targetWorld.getLevelName(),
                        targetWorld.getFolderPath());
            }
        };
        task.setOnSucceeded(done -> {
            if (world == targetWorld) {
                statusLabel.setText("已导出当前视口：" + task.getValue());
                updateExportState();
            }
        });
        task.setOnFailed(failed -> {
            LOGGER.error("Failed to export map viewport", task.getException());
            if (world == targetWorld) {
                offerAlternateExport(snapshot, task.getException());
                updateExportState();
            }
        });
        Thread thread = new Thread(task, "map-viewport-exporter");
        thread.setDaemon(true);
        thread.start();
    }

    private void offerAlternateExport(BufferedImage snapshot, Throwable failure) {
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "默认 exports 目录不可用。\n" + shortMessage(failure),
                ButtonType.OK,
                ButtonType.CANCEL);
        alert.initOwner(viewportHost.getScene().getWindow());
        alert.setTitle("选择其他导出位置");
        alert.setHeaderText("是否选择其他 PNG 保存位置？");
        if (alert.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 PNG 导出位置");
        chooser.setInitialFileName(exporter.suggestedFileName(world.getLevelName()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
        File target = chooser.showSaveDialog(viewportHost.getScene().getWindow());
        if (target == null) {
            return;
        }
        try {
            Path exported = exporter.exportToFile(
                    snapshot,
                    target.toPath(),
                    world.getFolderPath());
            statusLabel.setText("已导出当前视口：" + exported);
        } catch (IOException e) {
            LOGGER.error("Failed to export map viewport to {}", target, e);
            statusLabel.setText("导出失败：" + shortMessage(e));
        }
    }

    private void refreshTiles() {
        if (world == null || dimension == null || layer == null
                || viewport.getWidth() <= 1 || viewport.getHeight() <= 1) {
            updateStatus();
            return;
        }
        MapViewportState viewportState = viewport.viewportState();
        double focusX = viewportState.centerX();
        double focusZ = viewportState.centerZ();
        requestedKeys = coordinator.visibleKeys(
                com.mcworldexplorer.storage.WorldCachePaths.worldDirectoryName(world),
                dimension.id(),
                layer,
                viewportState,
                viewport.getWidth(),
                viewport.getHeight(),
                RENDER_VERSION);
        prefetchKeys = coordinator.prefetchKeys(
                com.mcworldexplorer.storage.WorldCachePaths.worldDirectoryName(world),
                dimension.id(),
                layer,
                viewportState,
                viewport.getWidth(),
                viewport.getHeight(),
                RENDER_VERSION);
        Set<MapTileKey> requested = Set.copyOf(requestedKeys);
        WorldInfo requestedWorld = world;
        WorldDimension requestedDimension = dimension;
        readyKeys.retainAll(requested);
        viewport.setTargetTiles(requestedKeys);
        long request = scheduler.beginRequest(requested);
        prefetchRequestId = -1;
        long taskContext = contextId;
        failedTiles = 0;
        for (int index = 0; index < requestedKeys.size(); index++) {
            MapTileKey key = requestedKeys.get(index);
            if (readyKeys.contains(key)) {
                continue;
            }
            scheduler.submit(
                    key,
                    index,
                    request,
                    cancellation -> tileService.load(
                            requestedWorld,
                            requestedDimension,
                            key,
                            new MapTileGenerationMonitor() {
                                @Override
                                public boolean isCancelled() {
                                    return cancellation.isCancelled();
                                }

                                @Override
                                public void onProgress(int completedChunks, int totalChunks) {
                                }

                                @Override
                                public double focusX() {
                                    return focusX;
                                }

                                @Override
                                public double focusZ() {
                                    return focusZ;
                                }

                                @Override
                                public void onPartial(MapTilePartialResult partial) {
                                    Platform.runLater(() -> acceptPartialTile(
                                            taskContext,
                                            key,
                                            partial));
                                }
                            }),
                    result -> Platform.runLater(() ->
                            acceptTile(taskContext, request, key, result)),
                    failure -> Platform.runLater(() ->
                            acceptTileFailure(taskContext, request, key, failure)));
        }
        updateStatus();
    }

    private void acceptTile(
            long taskContext,
            long request,
            MapTileKey key,
            MapTileCacheResult result) {
        if (taskContext != contextId || !scheduler.isCurrent(request)
                || !requestedKeys.contains(key)) {
            return;
        }
        readyKeys.add(key);
        viewport.showTile(key, toFxImage(result.image()), result.markers());
        updateStatus();
        if (readyKeys.size() == requestedKeys.size() && failedTiles == 0) {
            schedulePrefetch(taskContext, request);
        }
    }

    private void acceptPartialTile(
            long taskContext,
            MapTileKey key,
            MapTilePartialResult partial) {
        if (taskContext != contextId
                || !requestedKeys.contains(key) || readyKeys.contains(key)) {
            return;
        }
        viewport.showPartialTile(
                key,
                toFxImage(partial.image()),
                partial.markers());
        updateStatus();
    }

    private void acceptTileFailure(
            long taskContext,
            long request,
            MapTileKey key,
            Throwable failure) {
        if (taskContext != contextId || !scheduler.isCurrent(request)
                || !requestedKeys.contains(key)) {
            return;
        }
        failedTiles++;
        LOGGER.warn("Failed to load map tile {}", key, failure);
        updateStatus();
    }

    private void schedulePrefetch(long taskContext, long request) {
        if (prefetchRequestId == request || taskContext != contextId
                || !scheduler.isCurrent(request) || world == null || dimension == null) {
            return;
        }
        prefetchRequestId = request;
        WorldInfo requestedWorld = world;
        WorldDimension requestedDimension = dimension;
        double focusX = viewport.viewportState().centerX();
        double focusZ = viewport.viewportState().centerZ();
        for (int index = 0; index < prefetchKeys.size(); index++) {
            MapTileKey key = prefetchKeys.get(index);
            scheduler.submit(
                    key,
                    requestedKeys.size() + index,
                    request,
                    cancellation -> tileService.load(
                            requestedWorld,
                            requestedDimension,
                            key,
                            new MapTileGenerationMonitor() {
                                @Override
                                public boolean isCancelled() {
                                    return cancellation.isCancelled();
                                }

                                @Override
                                public double focusX() {
                                    return focusX;
                                }

                                @Override
                                public double focusZ() {
                                    return focusZ;
                                }
                            }),
                    result -> {
                    },
                    failure -> LOGGER.debug("Map prefetch failed for {}", key, failure));
        }
    }

    private void updateStatus() {
        if (world == null || viewport == null || viewport.viewportState() == null) {
            exportButton.setDisable(true);
            return;
        }
        MapViewportState state = viewport.viewportState();
        String loadingState = viewport.isShowingTemporaryScale()
                ? "临时缩放画面"
                : readyKeys.size() == requestedKeys.size() && failedTiles == 0
                        ? "已清晰加载"
                        : "正在补全";
        statusLabel.setText(String.format(
                "%s · 中心 X %.0f · Z %.0f · %.2f 方块/像素 · %d/%d 图块%s",
                loadingState,
                state.centerX(),
                state.centerZ(),
                state.visualBlocksPerPixel(),
                readyKeys.size(),
                requestedKeys.size(),
                failedTiles == 0 ? "" : " · " + failedTiles + " 个失败"));
        updateExportState();
    }

    private void updateExportState() {
        exportButton.setDisable(world == null
                || requestedKeys.isEmpty()
                || readyKeys.size() != requestedKeys.size()
                || failedTiles > 0);
    }

    private List<MapMarker> fixedMarkers() {
        if (world == null || dimension == null) {
            return List.of();
        }
        List<MapMarker> markers = new ArrayList<>();
        for (PlayerNavigation player : playerNavigations()) {
            if (player.dimensionId().equals(dimension.id())) {
                markers.add(MapMarker.point(
                        MapMarkerType.PLAYER,
                        dimension.id(),
                        floorToInt(player.x()),
                        floorToInt(player.y()),
                        floorToInt(player.z())));
            }
        }
        if (dimension.isOverworld() && world.isSpawnPositionAvailable()) {
            markers.add(MapMarker.point(
                    MapMarkerType.WORLD_SPAWN,
                    dimension.id(),
                    world.getSpawnX(),
                    world.getSpawnY(),
                    world.getSpawnZ()));
        }
        return List.copyOf(markers);
    }

    private void loadPlayers(WorldInfo requestedWorld, long loadId) {
        Task<List<PlayerLocation>> task = new Task<>() {
            @Override
            protected List<PlayerLocation> call() throws IOException {
                return playerDataReader.read(requestedWorld.getFolderPath());
            }
        };
        task.setOnSucceeded(event -> {
            if (loadId != playerLoadId || world != requestedWorld) {
                return;
            }
            playerLocations = task.getValue();
            refreshPlayerMenu();
            if (dimension != null) {
                viewport.setFixedMarkers(fixedMarkers());
            }
            updateStatus();
        });
        task.setOnFailed(event -> {
            if (loadId != playerLoadId || world != requestedWorld) {
                return;
            }
            LOGGER.warn(
                    "Failed to load player data for {}",
                    requestedWorld.getFolderPath(),
                    task.getException());
            playerLocations = List.of();
            refreshPlayerMenu();
            if (dimension != null) {
                viewport.setFixedMarkers(fixedMarkers());
            }
            statusLabel.setText("多人玩家数据读取失败，已使用单玩家位置");
        });
        Thread thread = new Thread(task, "map-player-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private List<PlayerNavigation> playerNavigations() {
        if (!playerLocations.isEmpty()) {
            return playerLocations.stream()
                    .map(MapViewerController::navigationFor)
                    .toList();
        }
        if (world == null || !world.isPlayerPositionAvailable()) {
            return List.of();
        }
        return List.of(new PlayerNavigation(
                "level.dat",
                "玩家",
                WorldDimension.normalizeId(world.getPlayerDimension()),
                world.getPlayerX(),
                world.getPlayerY(),
                world.getPlayerZ(),
                PreviewLayer.surfaceOverview(),
                DEFAULT_ZOOM));
    }

    private void refreshPlayerMenu() {
        if (playerListButton == null) {
            return;
        }
        List<PlayerNavigation> players = playerNavigations();
        Map<String, Integer> duplicateNames = new HashMap<>();
        for (PlayerNavigation player : players) {
            duplicateNames.merge(
                    player.displayName().toLowerCase(Locale.ROOT),
                    1,
                    Integer::sum);
        }
        List<MenuItem> items = new ArrayList<>();
        List<PlayerNavigation> sorted = players.stream()
                .sorted(java.util.Comparator
                        .comparing(
                                PlayerNavigation::displayName,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PlayerNavigation::identifier))
                .toList();
        for (PlayerNavigation player : sorted) {
            WorldDimension targetDimension = findDimension(player.dimensionId());
            boolean duplicate = duplicateNames.getOrDefault(
                    player.displayName().toLowerCase(Locale.ROOT),
                    0) > 1;
            String name = duplicate
                    ? player.displayName() + " [" + shortIdentifier(player.identifier()) + "]"
                    : player.displayName();
            String dimensionName = targetDimension == null
                    ? player.dimensionId() + "，维度不可用"
                    : targetDimension.displayName();
            MenuItem item = new MenuItem(name + "（" + dimensionName + "）");
            item.setOnAction(event -> navigateToPlayer(player));
            items.add(item);
        }
        playerListButton.getItems().setAll(items);
        playerListButton.setText(players.isEmpty()
                ? "玩家列表"
                : "玩家列表 (" + players.size() + ")");
        playerListButton.setDisable(players.isEmpty());
    }

    private void navigateToPlayer(PlayerNavigation navigation) {
        WorldDimension targetDimension = findDimension(navigation.dimensionId());
        if (targetDimension == null) {
            statusLabel.setText(
                    "无法定位玩家 " + navigation.displayName()
                            + "：未发现维度 " + navigation.dimensionId());
            return;
        }
        pendingPlayerNavigation = navigation;
        if (Objects.equals(dimension, targetDimension)) {
            selectDimension(targetDimension);
        } else {
            dimensionComboBox.getSelectionModel().select(targetDimension);
        }
    }

    private WorldDimension findDimension(String dimensionId) {
        if (dimensionComboBox == null) {
            return null;
        }
        String normalized = WorldDimension.normalizeId(dimensionId);
        return dimensionComboBox.getItems().stream()
                .filter(candidate -> candidate.id().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private void setLayerControlsDisabled(boolean disabled) {
        surfaceOverviewButton.setDisable(disabled);
        layerHeightSlider.setDisable(disabled);
    }

    private static int defaultSliderY(DimensionHeightRange range, PreviewLayer selected) {
        if (!selected.isSurfaceOverview()) {
            return selected.minY() + (selected.maxY() - selected.minY()) / 2;
        }
        return MainController.sliderCoordinate(range, 64);
    }

    static PreviewRequest initialMapRequest(
            WorldInfo world,
            WorldDimension dimension,
            DimensionHeightRange range) {
        return PreviewRequestResolver.resolve(
                world,
                dimension,
                range,
                PreviewLayer.surfaceOverview());
    }

    private static void configureMarkerToggle(
            ToggleButton toggle,
            MapMarkerType type) {
        Circle swatch = new Circle(4, Color.web(MapMarkerStyle.hex(type)));
        swatch.getStyleClass().addAll(
                "marker-swatch",
                MapMarkerStyle.toggleStyleClass(type) + "-swatch");
        toggle.setGraphic(swatch);
        toggle.getStyleClass().addAll(
                "marker-toggle",
                MapMarkerStyle.toggleStyleClass(type));
    }

    private static WritableImage toFxImage(BufferedImage source) {
        WritableImage image = new WritableImage(source.getWidth(), source.getHeight());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                image.getPixelWriter().setArgb(x, y, source.getRGB(x, y));
            }
        }
        return image;
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    static PlayerNavigation navigationFor(PlayerLocation player) {
        Objects.requireNonNull(player, "player");
        return new PlayerNavigation(
                player.uuid().toString(),
                player.displayName(),
                player.dimensionId(),
                player.x(),
                player.y(),
                player.z(),
                PreviewLayer.surfaceOverview(),
                DEFAULT_ZOOM);
    }

    private static String shortIdentifier(String identifier) {
        return identifier.length() <= 8 ? identifier : identifier.substring(0, 8);
    }

    private static String shortMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause == null) {
            return "请查看日志";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private record DimensionState(
            DimensionHeightRange range,
            int sliderY,
            PreviewLayer layer,
            MapViewportState viewportState) {
        DimensionState withSliderY(int value) {
            return new DimensionState(range, value, layer, viewportState);
        }

        DimensionState withLayer(PreviewLayer value) {
            return new DimensionState(range, sliderY, value, viewportState);
        }
    }

    record PlayerNavigation(
            String identifier,
            String displayName,
            String dimensionId,
            double x,
            double y,
            double z,
            PreviewLayer layer,
            MapZoomLevel zoom) {

        PlayerNavigation {
            if (identifier == null || identifier.isBlank()
                    || displayName == null || displayName.isBlank()
                    || dimensionId == null || dimensionId.isBlank()) {
                throw new IllegalArgumentException("player navigation identity must be present");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("player navigation coordinates must be finite");
            }
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(zoom, "zoom");
        }
    }
}
