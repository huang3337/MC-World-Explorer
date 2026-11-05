package com.mcworldexplorer.ui;

import com.mcworldexplorer.voxel.mesh.MeshWarning;
import com.mcworldexplorer.voxel.resource.ClientResourceLocator;
import com.mcworldexplorer.voxel.resource.ClientResourceArchive;
import com.mcworldexplorer.voxel.resource.ResourceLocation;
import com.mcworldexplorer.voxel.resource.ModelPluginCatalog;
import com.mcworldexplorer.voxel.resource.ModelPluginStore;
import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.world.WorldScanner;
import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerType;
import com.mcworldexplorer.map.MapTileCacheResult;
import com.mcworldexplorer.map.MapTileGenerationMonitor;
import com.mcworldexplorer.map.MapTilePartialResult;
import com.mcworldexplorer.map.MapTileKey;
import com.mcworldexplorer.map.MapTileLoadTracker;
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
import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.viewer.ViewerMapReturnState;
import com.mcworldexplorer.viewer.ViewerPerformanceProfile;
import com.mcworldexplorer.viewer.ViewerProgress;
import com.mcworldexplorer.viewer.ViewerRangeOption;
import com.mcworldexplorer.viewer.ViewerRequest;
import com.mcworldexplorer.viewer.ViewerCachedArea;
import com.mcworldexplorer.viewer.ViewerTaskListener;
import com.mcworldexplorer.viewer.ViewerTaskResult;
import com.mcworldexplorer.viewer.ResourceDiagnosticReport;
import com.mcworldexplorer.viewer.VoxelPreviewCoordinator;
import com.mcworldexplorer.viewer.lwjgl.LwjglViewerWindow;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.scene.control.TextArea;
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
    private final PortableSettings portableSettings = new PortableSettings();
    private javafx.scene.control.Dialog<Void> resourceSettingsDialog;
    private ResourceSettingsPane resourceSettingsPane;
    private ModelPluginCatalog modelPlugins=ModelPluginCatalog.core();
    private ModelPluginCatalog.Selection modelPluginSelection=modelPlugins.select(Set.of());
    private ModelPluginLibraryController modelPluginLibrary;
    private String modelPluginNotice="";
    private Map<ResourceLocation,String> modelPluginExecutions=Map.of();
    private final MapTileLoadTracker tileLoadTracker = new MapTileLoadTracker();
    private final PauseTransition viewportDebounce = new PauseTransition(Duration.millis(200));
    private final PauseTransition playerHighlight = new PauseTransition(Duration.seconds(3));
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
    private boolean cacheMaintenanceInProgress;
    private long contextId;
    private long playerLoadId;
    private long cacheMaintenanceId;
    private long currentRequestId = -1;
    private long prefetchRequestId = -1;
    private VoxelPreviewCoordinator viewerCoordinator;
    private ViewerPerformanceProfile viewerProfile = ViewerPerformanceProfile.BASIC;
    private ViewerRangeOption viewerRange = ViewerRangeOption.THREE;
    private ChunkRectangle viewerLockedSelection;
    private ChunkRectangle activeViewerTarget;
    private long activeViewerRequestId = -1;
    private boolean viewerTaskRunning;
    private boolean shuttingDown;
    private long viewerCacheInspectionId;
    private Set<ChunkRectangle> validViewerCachedAreas = Set.of();
    private boolean activeViewerExpectedFromCache;
    private List<ViewerCachedArea> viewerCachedPreviews = List.of();
    private ViewerAreaList viewerAreaList;
    private Path viewerResourcePath;
    private Path viewerResourceInstance;
    private String viewerResourceDiagnostic="尚未生成三维区域";
    private ResourceDiagnosticReport viewerDiagnosticReport=ResourceDiagnosticReport.empty();
    private long viewerResourceLookupId;
    private Task<ClientResourceLocator.Result> viewerResourceLookup;
    private java.util.function.Supplier<List<Path>> clientResourceRoots = this::defaultClientResourceRoots;
    @FXML private MenuButton viewerTextureMenu;
    @FXML private Label viewerTextureHelp;

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
    private MenuButton locateMenuButton;
    @FXML
    private MenuButton layersMenuButton;
    @FXML
    private MenuButton moreMenuButton;
    @FXML
    private ToggleButton spawnMarkerButton;
    @FXML
    private ToggleButton portalMarkerButton;
    @FXML
    private Button exportButton;
    @FXML
    private Button clearCacheButton;
    @FXML
    private ToggleButton viewerModeButton;
    @FXML
    private VBox viewerSelectionBar;
    @FXML
    private ComboBox<ViewerRangeOption> viewerRangeComboBox;
    @FXML
    private ComboBox<ViewerPerformanceProfile> viewerProfileComboBox;
    @FXML
    private Label viewerSelectionLabel;
    @FXML
    private ProgressBar viewerProgressBar;
    @FXML
    private Button viewerConfirmButton;
    @FXML
    private Button viewerCancelButton;
    @FXML
    private Label statusLabel;
    @FXML
    private StackPane viewportHost;
    @FXML
    private StackPane viewerAreasHost;
    @FXML
    private VBox mapZoomControls;
    @FXML
    private ToggleButton viewerAreasButton;

    private MapViewport viewport;
    private VBox mapPlaceholder;

    private void configureTextureHelp() {
        Tooltip tip = viewerTextureHelp.getTooltip();
        // Anchor outside the small hover target; do not let the popup cover it
        // or compete with Tooltip's timed mouse-following behaviour.
        Tooltip.uninstall(viewerTextureHelp, tip);
        tip.setAutoFix(false);
        tip.setAutoHide(false);
        tip.getScene().getRoot().setMouseTransparent(true);
        viewerTextureHelp.setOnMouseEntered(event -> {
            var anchor = viewerTextureHelp.localToScreen(viewerTextureHelp.getBoundsInLocal());
            if (anchor == null) return;
            var screens = javafx.stage.Screen.getScreensForRectangle(anchor.getMinX(), anchor.getMinY(),
                    anchor.getWidth(), anchor.getHeight());
            var screen = (screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.getFirst())
                    .getVisualBounds();
            tip.show(viewerTextureHelp, anchor.getMinX(), anchor.getMaxY() + 8);
            var content = tip.getScene().getRoot();
            content.applyCss();
            content.layout();
            tip.setX(Math.max(screen.getMinX(), Math.min(anchor.getMinX(), screen.getMaxX() - tip.getWidth())));
            double below = anchor.getMaxY() + 8;
            tip.setY(below + tip.getHeight() <= screen.getMaxY()
                    ? below : anchor.getMinY() - tip.getHeight() - 8);
        });
        viewerTextureHelp.setOnMouseExited(event -> tip.hide());
        viewerTextureHelp.sceneProperty().addListener((observable, previous, current) -> tip.hide());
        viewerSelectionBar.visibleProperty().addListener((observable, previous, visible) -> {
            if (!visible) tip.hide();
        });
    }

    @FXML
    public void initialize() {
        configureTextureHelp();
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(statusLabel.textProperty());
        statusLabel.setTooltip(statusTooltip);
        Tooltip selectionTooltip = new Tooltip();
        selectionTooltip.textProperty().bind(viewerSelectionLabel.textProperty());
        viewerSelectionLabel.setTooltip(selectionTooltip);
        viewerSelectionLabel.textProperty().addListener((observable,previous,current)->refreshResourceSettings());
        modelPluginLibrary=new ModelPluginLibraryController(()->!viewerTaskRunning&&!cacheMaintenanceInProgress&&!shuttingDown,
            this::acceptPluginLibrarySnapshot,text->{modelPluginNotice=text;if(resourceSettingsPane!=null)resourceSettingsPane.showPluginFeedback(text);refreshResourceSettings();});
        modelPluginLibrary.initialize();
        viewerAreaList = new ViewerAreaList(() -> {
            setViewerAreasVisible(false);
            viewerAreasButton.requestFocus();
        });
        viewerAreaList.setOnOpen(this::reopenViewerArea);
        viewerAreaList.setOnRemove(this::removeViewerArea);
        viewerAreaList.setOnClear(() -> removeViewerAreas(viewerCachedPreviews, true));
        viewerAreasHost.getChildren().setAll(viewerAreaList);
        viewport = new MapViewport();
        Label placeholderTitle = new Label("开始浏览你的世界");
        placeholderTitle.getStyleClass().add("map-placeholder-title");
        Label placeholderMessage = new Label();
        placeholderMessage.textProperty().bind(statusLabel.textProperty());
        placeholderMessage.setWrapText(true);
        placeholderMessage.setMaxWidth(320);
        mapPlaceholder = new VBox(10, placeholderTitle, placeholderMessage);
        mapPlaceholder.setAlignment(Pos.CENTER);
        mapPlaceholder.setMouseTransparent(true);
        mapPlaceholder.getStyleClass().add("map-placeholder");
        mapPlaceholder.managedProperty().bind(mapPlaceholder.visibleProperty());
        viewportHost.getChildren().setAll(viewport, mapPlaceholder);
        viewerAreasButton.disableProperty().bind(viewerModeButton.disableProperty());
        locateMenuButton.disableProperty().bind(viewerModeButton.disableProperty());
        layersMenuButton.disableProperty().bind(surfaceOverviewButton.disableProperty());
        viewport.setOnViewportChanged(() -> {
            updateStatus();
            viewportDebounce.playFromStart();
        });
        viewport.setOnVisualChanged(this::updateStatus);
        viewport.setOnZoomTargetChanged(() -> {
            tileLoadTracker.clear();
            updateStatus();
            refreshTiles();
        });
        viewport.setOnRetryRequested(this::retryTile);
        viewport.setOnViewerSelectionLocked(this::lockViewerSelection);
        viewport.setOnViewerSelectionChanged(this::previewViewerSelection);
        viewport.setOnViewerEscapeRequested(this::handleViewerEscape);
        playerHighlight.setOnFinished(event -> viewport.clearPlayerHighlight());
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
        configureViewerControls();
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
            viewerResourceInstance=portableSettings.loadResourceInstancePath(world.getFolderPath(),world.getVersionName()).filter(java.nio.file.Files::isDirectory).orElse(null);
        }catch(IOException invalid){LOGGER.warn("忽略不可用的资源实例绑定",invalid);}
        startViewerResourceLookup(null);
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
        cancelViewerResourceLookup();
        viewerResourcePath = null;
        viewerResourceInstance=null;invalidateResourceDiagnostics("尚未生成三维区域");
        if (viewerTextureMenu != null) { viewerTextureMenu.setText("纹理：自动"); viewerTextureMenu.setTooltip(null); }
        if (mapPlaceholder != null) {
            mapPlaceholder.setVisible(true);
            locateMenuButton.hide();
            layersMenuButton.hide();
            moreMenuButton.hide();
        }
        cancelViewerTask(false);
        exitViewerMode(false);
        contextId++;
        playerLoadId++;
        cacheMaintenanceId++;
        viewerCacheInspectionId++;
        viewportDebounce.stop();
        playerHighlight.stop();
        scheduler.cancelAll();
        tileService.clearMemory();
        tileLoadTracker.clear();
        readyKeys.clear();
        requestedKeys = List.of();
        prefetchKeys = List.of();
        playerLocations = List.of();
        pendingPlayerNavigation = null;
        cacheMaintenanceInProgress = false;
        refreshResourceSettings();
        dimensionStates.clear();
        world = null;
        dimension = null;
        if (viewerAreaList != null) {
            updateViewerAreaList(List.of());
            setViewerAreasVisible(false);
        }
        layer = null;
        currentRequestId = -1;
        if (viewport != null) {
            viewport.setViewportState(null);
            viewport.setFixedMarkers(List.of());
            viewport.clearPlayerHighlight();
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
            viewerModeButton.setDisable(true);
            coordinateErrorLabel.setText("");
            playerListButton.getItems().clear();
            playerListButton.setText("玩家列表");
            playerListButton.setDisable(true);
            statusLabel.setText("选择存档后浏览地图");
        }
    }

    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        if(resourceSettingsDialog!=null)resourceSettingsDialog.close();
        if(modelPluginLibrary!=null)modelPluginLibrary.close();
        viewerTextureHelp.getTooltip().hide();
        cancelViewerResourceLookup();
        contextId++;
        playerLoadId++;
        cacheMaintenanceId++;
        viewerCacheInspectionId++;
        activeViewerRequestId = -1;
        viewerTaskRunning = false;
        viewportDebounce.stop();
        playerHighlight.stop();
        scheduler.close();
        if (viewerCoordinator != null) {
            viewerCoordinator.close();
            viewerCoordinator = null;
        }
    }

    private void selectDimension(WorldDimension selected) {
        cancelViewerTask(false);
        invalidateResourceDiagnostics("维度已切换，生成三维预览后查看该选区的解析报告。");
        refreshResourceSettings();
        exitViewerMode(false);
        playerHighlight.stop();
        viewport.clearPlayerHighlight();
        if (pendingPlayerNavigation != null
                && !pendingPlayerNavigation.dimensionId().equals(selected.id())) {
            pendingPlayerNavigation = null;
        }
        contextId++;
        scheduler.cancelAll();
        tileLoadTracker.clear();
        readyKeys.clear();
        currentRequestId = -1;
        dimension = selected;
        viewerCacheInspectionId++;
        updateViewerAreaList(List.of());
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
        mapPlaceholder.setVisible(false);
        viewport.setFixedMarkers(fixedMarkers());
        viewport.setViewerCachedAreaContext(dimension.displayName());
        viewerModeButton.setDisable(false);
        refreshViewerCachedAreas();
        if (navigation != null && navigation.dimensionId().equals(dimension.id())) {
            playerMarkerButton.setSelected(true);
            viewport.highlightPlayer(navigation.identifier());
            playerHighlight.playFromStart();
        }
        refreshTiles();
    }

    private void configureViewerControls() {
        try {
            viewerProfile = portableSettings.loadViewerPerformanceProfile()
                    .map(ViewerPerformanceProfile::fromSetting)
                    .orElse(ViewerPerformanceProfile.BASIC);
        } catch (IOException e) {
            LOGGER.warn("Failed to read viewer performance profile", e);
            viewerProfile = ViewerPerformanceProfile.BASIC;
        }
        viewerProfileComboBox.setItems(FXCollections.observableArrayList(
                ViewerPerformanceProfile.values()));
        viewerProfileComboBox.getSelectionModel().select(viewerProfile);
        replaceViewerRanges(viewerRange);
        viewerRangeComboBox.setTooltip(new Tooltip("自由框选：左键拖动选择，取消选区后可重新框选；右键拖动地图，滚轮缩放，Esc 取消。增强档最多 256 个区块，基础档最多 9 个，单边最多 32。"));
        viewerProfileComboBox.valueProperty().addListener((observable, oldValue, value) -> {
            if (value == null || value == viewerProfile) {
                return;
            }
            if (viewerTaskRunning) {
                cancelViewerTask(false);
            }
            viewerProfile = value;
            viewerRange = viewerRange.allowedBy(viewerProfile) ? viewerRange : ViewerRangeOption.THREE;
            replaceViewerRanges(viewerRange);
            configureViewportRange();
            clearViewerSelection(viewerSelectionHint());
            if (viewerCoordinator != null) {
                viewerCoordinator.setProfile(viewerProfile);
                refreshViewerCachedAreas();
            }
            try {
                portableSettings.saveViewerPerformanceProfile(viewerProfile.settingValue());
            } catch (IOException e) {
                LOGGER.warn("Failed to save viewer performance profile", e);
                statusLabel.setText("性能档位已生效，但无法保存本地配置");
            }
        });
        viewerRangeComboBox.valueProperty().addListener((observable, oldValue, value) -> {
            if (value == null || value == viewerRange) {
                return;
            }
            if (viewerTaskRunning) {
                cancelViewerTask(false);
            }
            viewerRange = value;
            configureViewportRange();
            clearViewerSelection(viewerSelectionHint());
        });
    }

    private void replaceViewerRanges(ViewerRangeOption preferred) {
        List<ViewerRangeOption> ranges = viewerRangesForProfile(viewerProfile);
        viewerRangeComboBox.setItems(FXCollections.observableArrayList(ranges));
        viewerRangeComboBox.getSelectionModel().select(
                ranges.contains(preferred) ? preferred : ViewerRangeOption.THREE);
        viewerRange = viewerRangeComboBox.getValue();
    }

    private void configureViewportRange() {
        viewport.setViewerFreeSelection(viewerRange.isFree(), viewerProfile);
        if (!viewerRange.isFree()) {
            viewport.setViewerRangePreset(viewerRange.preset());
        }
    }

    private String viewerSelectionHint() {
        return viewerRange.isFree()
                ? "左键拖动框选 · 右键拖动地图 · 单边最多 32、总计 "
                        + viewerProfile.maxTargetChunks() + " 个区块 · Esc 取消"
                : "在地图上点击以锁定三维区域";
    }

    private void previewViewerSelection(ChunkRectangle selection) {
        if (viewerTaskRunning || viewerLockedSelection != null) return;
        activeViewerTarget = null;
        viewerConfirmButton.setDisable(true);
        long width = (long) selection.maxX() - selection.minX() + 1;
        long depth = (long) selection.maxZ() - selection.minZ() + 1;
        viewerSelectionLabel.setText(String.format("%d x %d（%d 个区块）%s", width, depth,
                width * depth, viewerProfile.allows(selection) ? " · 松开鼠标锁定"
                        : " · 超出上限：单边 32，总计 " + viewerProfile.maxTargetChunks() + " 个区块"));
    }

    static List<ViewerRangeOption> viewerRangesForProfile(ViewerPerformanceProfile profile) {
        return java.util.Arrays.stream(ViewerRangeOption.values())
                .filter(option -> option.allowedBy(profile))
                .toList();
    }

    @FXML
    private void handleViewerMode(ActionEvent event) {
        if (viewerModeButton.isSelected()) {
            enterViewerMode();
        } else {
            exitViewerMode(true);
        }
    }

    @FXML
    private void handleViewerConfirm(ActionEvent event) {
        startViewerTask();
    }

    @FXML
    private void handleViewerCancel(ActionEvent event) {
        boolean wasRunning = viewerTaskRunning;
        cancelViewerTask(false);
        clearViewerSelection((wasRunning ? "已取消生成；" : "") + viewerSelectionHint());
    }

    private void enterViewerMode() {
        if (world == null || dimension == null || layer == null) {
            viewerModeButton.setSelected(false);
            return;
        }
        viewerSelectionBar.setManaged(true);
        viewerSelectionBar.setVisible(true);
        configureViewportRange();
        viewport.setViewerSelectionMode(true);
        refreshViewerCachedAreas();
        clearViewerSelection(viewerSelectionHint());
        viewport.requestFocus();
    }

    private void exitViewerMode(boolean cancelTask) {
        if (cancelTask) {
            cancelViewerTask(false);
        }
        viewerLockedSelection = null;
        activeViewerTarget = null;
        if (viewport != null) {
            viewport.setViewerSelectionMode(false);
            viewport.setViewerCachedAreas(List.of());
        }
        if (viewerModeButton != null) {
            viewerModeButton.setSelected(false);
            viewerSelectionBar.setManaged(false);
            viewerSelectionBar.setVisible(false);
            setViewerTaskUi(false);
        }
    }

    private void handleViewerEscape() {
        if (viewerTaskRunning || viewerLockedSelection != null) {
            handleViewerCancel(null);
        } else {
            exitViewerMode(false);
        }
    }

    private void lockViewerSelection(ChunkRectangle selection) {
        if (viewerTaskRunning || !viewport.isViewerSelectionMode() || !viewerProfile.allows(selection)
                || (viewerLockedSelection != null && !viewerLockedSelection.equals(selection))) {
            return;
        }
        viewerLockedSelection = selection;
        viewport.lockViewerSelection(selection);
        activeViewerTarget = null;
        viewerSelectionLabel.setText(formatViewerSelection(selection));
        viewerConfirmButton.setText(isCachedArea(selection) ? "打开三维预览" : "生成三维预览");
        updateViewerSelectionActions();
    }

    private void clearViewerSelection(String message) {
        viewerLockedSelection = null;
        activeViewerTarget = null;
        if (viewport != null) {
            viewport.clearViewerLockedSelection();
        }
        if (viewerSelectionLabel != null) {
            viewerSelectionLabel.setText(message);
            viewerConfirmButton.setText("生成三维预览");
            viewerConfirmButton.setDisable(true);
        }
        updateViewerSelectionActions();
    }

    static String formatViewerSelection(ChunkRectangle selection) {
        long centerX = (long) selection.minX() * 16 + (long) selection.width() * 8;
        long centerZ = (long) selection.minZ() * 16 + (long) selection.depth() * 8;
        return String.format(
                "%d x %d（%d 个）· 区块 X %d..%d · Z %d..%d · 中心 X %d · Z %d",
                selection.width(), selection.depth(),
                selection.width() * selection.depth(),
                selection.minX(), selection.maxX(), selection.minZ(), selection.maxZ(),
                centerX, centerZ);
    }

    private boolean isCachedArea(ChunkRectangle selection) {
        return validViewerCachedAreas.contains(selection);
    }

    @FXML
    private void handleViewerAreas(ActionEvent event) {
        setViewerAreasVisible(viewerAreasButton.isSelected());
        if (viewerAreasButton.isSelected()) refreshViewerCachedAreas();
    }

    private void setViewerAreasVisible(boolean visible) {
        viewerAreasButton.setSelected(visible);
        viewerAreasHost.setVisible(visible);
        viewerAreasHost.setManaged(visible);
        StackPane.setMargin(mapZoomControls, new javafx.geometry.Insets(0, visible ? 320 : 16, 16, 0));
    }

    private void updateViewerAreaList(List<ViewerCachedArea> areas) {
        viewerCachedPreviews = List.copyOf(areas);
        validViewerCachedAreas = areas.stream().map(area -> area.key().target())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        viewport.setViewerCachedAreas(validViewerCachedAreas);
        viewerAreasButton.setText("已加载区域 · " + areas.size());
        viewerAreaList.setAreas(areas, viewerProfile, viewerTaskRunning);
    }

    private void removeViewerArea(ViewerCachedArea area) {
        removeViewerAreas(List.of(area), false);
    }

    private void removeViewerAreas(List<ViewerCachedArea> areas, boolean clearList) {
        if (world == null || dimension == null || viewerCoordinator == null || viewerTaskRunning
                || cacheMaintenanceInProgress) return;
        var keys = areas.stream().map(ViewerCachedArea::key)
                .filter(key -> key.world().equals(world.getFolderPath().toAbsolutePath().normalize())
                        && key.dimensionId().equals(dimension.id()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (keys.isEmpty()) return;
        viewerCacheInspectionId++;
        keys.forEach(viewerCoordinator::removeCachedArea);
        updateViewerAreaList(viewerCachedPreviews.stream().filter(candidate -> !keys.contains(candidate.key())).toList());
        if (keys.stream().anyMatch(key -> key.target().equals(viewerLockedSelection))) {
            viewport.invalidateViewerLoadedSelection();
            viewerConfirmButton.setText("生成三维预览");
        }
        statusLabel.setText(clearList ? "已清空当前列表及对应三维内存缓存；原存档未修改"
                : "已删除区域及三维内存缓存；原存档未修改");
        refreshViewerCachedAreas();
    }

    private void reopenViewerArea(ViewerCachedArea area) {
        if (world == null || dimension == null || viewerTaskRunning || cacheMaintenanceInProgress
                || !area.key().world().equals(world.getFolderPath().toAbsolutePath().normalize())
                || !area.key().dimensionId().equals(dimension.id())
                || !viewerProfile.allows(area.key().target())) return;
        enterViewerMode();
        viewport.centerOn((long) area.key().target().minX() * 16 + area.key().target().width() * 8L,
                (long) area.key().target().minZ() * 16 + area.key().target().depth() * 8L);
        viewerModeButton.setSelected(true);
        viewport.lockViewerSelection(area.key().target());
        lockViewerSelection(area.key().target());
        startViewerTask();
    }

    private void refreshViewerCachedAreas() {
        if (viewerCoordinator == null || world == null || dimension == null) {
            updateViewerAreaList(List.of());
            return;
        }
        long inspectionId = ++viewerCacheInspectionId;
        Path worldPath = world.getFolderPath().toAbsolutePath().normalize();
        String dimensionId = dimension.id();
        VoxelPreviewCoordinator currentCoordinator = viewerCoordinator;
        validViewerCachedAreas = Set.of();
        viewport.setViewerCachedAreas(List.of());
        Task<List<ViewerCachedArea>> task = new Task<>() {
            @Override
            protected List<ViewerCachedArea> call() {
                return currentCoordinator.validateCachedPreviews().stream()
                        .filter(area -> area.key().world().equals(worldPath)
                                && area.key().dimensionId().equals(dimensionId))
                        .toList();
            }
        };
        task.setOnSucceeded(event -> {
            if (inspectionId != viewerCacheInspectionId || world == null || dimension == null
                    || !world.getFolderPath().toAbsolutePath().normalize().equals(worldPath)
                    || !dimension.id().equals(dimensionId)) {
                return;
            }
            updateViewerAreaList(task.getValue());
        });
        task.setOnFailed(event -> {
            LOGGER.debug("Failed to validate viewer cache", task.getException());
            if (inspectionId == viewerCacheInspectionId) updateViewerAreaList(List.of());
        });
        Thread thread = new Thread(task, "viewer-cache-validator");
        thread.setDaemon(true);
        thread.start();
    }

    private VoxelPreviewCoordinator ensureViewerCoordinator() {
        if (shuttingDown) {
            throw new IllegalStateException("应用正在关闭");
        }
        if (viewerCoordinator == null) {
            viewerCoordinator = new VoxelPreviewCoordinator(
                    new LwjglViewerWindow(), viewerProfile);
        }
        viewerCoordinator.setModelRules(modelPluginSelection.rules());
        viewerCoordinator.setResource(viewerResourcePath, world.getVersionName(),viewerResourceInstance);
        return viewerCoordinator;
    }

    @FXML
    private void handleViewerTexture(ActionEvent event) {
        if (viewerTaskRunning || shuttingDown) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择与存档版本对应的 Minecraft Java 客户端 JAR");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("客户端 JAR", "*.jar"));
        File selected = chooser.showOpenDialog(resourceSettingsWindow());
        if (selected == null) return;
        startViewerResourceLookup(selected.toPath());
    }

    @FXML
    private void handleViewerSimpleColors(ActionEvent event) {
        if (!viewerTaskRunning && !shuttingDown) {
            cancelViewerResourceLookup();
            setViewerResource(null);
        }
    }

    @FXML
    private void handleViewerAutomaticTexture(ActionEvent event) {
        if(world==null||!world.isParsed()||viewerTaskRunning||shuttingDown)return;
        cancelViewerResourceLookup();
        try{portableSettings.clearResourceBindings(world.getFolderPath(),world.getVersionName());}
        catch(IOException failure){
            viewerTextureMenu.setText(viewerResourcePath==null?"纹理：简化颜色":"纹理：手动资源");
            viewerSelectionLabel.setText("恢复自动匹配失败："+shortMessage(failure));return;
        }
        viewerResourceInstance=null;
        setViewerResource(null);
        viewport.invalidateViewerLoadedSelection();
        updateViewerAreaList(List.of());
        invalidateResourceDiagnostics("已恢复自动匹配资源，重新生成后显示诊断");
        startViewerResourceLookup(null);
    }

    @FXML private void handleViewerResourceInstance(ActionEvent event) {
        if(world==null||viewerTaskRunning||shuttingDown)return;
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("选择存档所属的游戏目录（包含 mods、resourcepacks 或 saves）");
        if(viewerResourceInstance!=null&&java.nio.file.Files.isDirectory(viewerResourceInstance))chooser.setInitialDirectory(viewerResourceInstance.toFile());
        File selected=chooser.showDialog(resourceSettingsWindow());if(selected==null)return;
        Path path=selected.toPath().toAbsolutePath().normalize();
        if(java.util.stream.Stream.of("mods","resourcepacks","saves","options.txt").noneMatch(name->java.nio.file.Files.exists(path.resolve(name)))) {
            viewerSelectionLabel.setText("请选择游戏目录，包含 mods、resourcepacks、saves 或 options.txt");return;
        }
        changeResourceInstance(path);
    }
    private void changeResourceInstance(Path path) {
        try{portableSettings.saveResourceInstancePath(world.getFolderPath(),world.getVersionName(),path);}
        catch(IOException failure){viewerSelectionLabel.setText("游戏目录绑定保存失败："+shortMessage(failure));return;}
        viewerResourceInstance=path;
        if(viewerCoordinator!=null)viewerCoordinator.setResource(viewerResourcePath,world.getVersionName(),path);
        viewerCacheInspectionId++;updateViewerAreaList(List.of());
        viewport.invalidateViewerLoadedSelection();
        invalidateResourceDiagnostics("游戏目录已改变，重新生成后显示诊断");
        viewerSelectionLabel.setText("已选择游戏目录，可重新生成三维区域");
        if(viewerLockedSelection!=null)viewerConfirmButton.setText("生成三维预览");
        viewerTextureMenu.setTooltip(new Tooltip("游戏目录："+path));
    }
    @FXML private void handleViewerResourceDiagnostics(ActionEvent event) {
        openResourceSettings(true);
    }
    @FXML private void handleResourceSettings(ActionEvent event){moreMenuButton.hide();openResourceSettings(false);}


    private javafx.stage.Window resourceSettingsWindow(){return resourceSettingsDialog!=null&&resourceSettingsDialog.isShowing()?resourceSettingsDialog.getDialogPane().getScene().getWindow():viewportHost.getScene().getWindow();}
    private void openResourceSettings(boolean diagnostics){
        if(shuttingDown)return;
        if(resourceSettingsDialog!=null){if(diagnostics)resourceSettingsPane.showDiagnostics();resourceSettingsDialog.getDialogPane().getScene().getWindow().requestFocus();return;}
        var dialog=new javafx.scene.control.Dialog<Void>();dialog.setTitle("资源与插件");dialog.initOwner(viewportHost.getScene().getWindow());dialog.setResizable(true);
        resourceSettingsPane=new ResourceSettingsPane(modelPlugins,modelPluginSelection.enabled(),new ResourceSettingsPane.Host(){
            public String world(){return world==null?"尚未选择存档":world.getLevelName();}
            public String version(){return world==null?"—":world.getVersionName();}
            public String client(){return viewerResourcePath==null?"自动查找或使用简化颜色":viewerResourcePath.toString();}
            public String instance(){return viewerResourceInstance==null?"随存档自动查找":viewerResourceInstance.toString();}
            public String status(){return world==null?"选择存档后可设置对应资源；模型适配设置可直接调整。":viewerTaskRunning?"正在生成预览，完成后可更改设置。":cacheMaintenanceInProgress?"正在清理缓存，完成后可更改设置。":viewerResourceLookup!=null?"正在查找或校验资源…":viewerSelectionLabel.getText();}
            public String diagnostics(){return viewerResourceDiagnostic;}
            public ResourceDiagnosticReport diagnosticReport(){return viewerDiagnosticReport;}
            public void exportDiagnostics(){exportResourceDiagnostics();}
            public void openPluginGuide(){PluginGuideDialog.show(resourceSettingsWindow());}
            public String pluginNotice(){return modelPluginNotice;}
            public boolean canApply(){return !viewerTaskRunning&&!cacheMaintenanceInProgress&&!shuttingDown&&!pluginOperationRunning()&&modelPluginLibrary!=null&&modelPluginLibrary.ready();}
            public void applyAsync(Set<ResourceLocation> enabled,java.util.function.Consumer<Exception> completion){modelPluginLibrary.apply(enabled,completion);}
            public boolean externalReady(){return modelPluginLibrary!=null&&modelPluginLibrary.ready();}
            public boolean canReloadPlugins(){return !viewerTaskRunning&&!cacheMaintenanceInProgress&&!shuttingDown&&!pluginOperationRunning();}
            public boolean pluginOperationRunning(){return MapViewerController.this.pluginOperationRunning();}
            public Set<ResourceLocation> externalPlugins(){return modelPluginLibrary==null?Set.of():modelPluginLibrary.external();}
            public void installPlugin(){modelPluginLibrary.choosePackage(resourceSettingsWindow(),null);}
            public void updatePlugin(ResourceLocation id){modelPluginLibrary.choosePackage(resourceSettingsWindow(),id);}
            public void uninstallPlugin(ResourceLocation id){modelPluginLibrary.uninstall(resourceSettingsWindow(),id);}
            public void reloadPlugins(){modelPluginLibrary.reload();}
            public boolean resourceActionsAvailable(){return !viewerTaskRunning&&!cacheMaintenanceInProgress&&!shuttingDown&&!pluginOperationRunning()&&world!=null&&world.isParsed()&&viewerResourceLookup==null;}
            public void automatic(){handleViewerAutomaticTexture(new ActionEvent());}
            public void chooseClient(){handleViewerTexture(new ActionEvent());}
            public void chooseInstance(){handleViewerResourceInstance(new ActionEvent());}
            public void simpleColors(){handleViewerSimpleColors(new ActionEvent());}
        });
        dialog.getDialogPane().setContent(resourceSettingsPane);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
        dialog.setOnHidden(e->{resourceSettingsDialog=null;resourceSettingsPane=null;});resourceSettingsDialog=dialog;dialog.show();
        if(diagnostics)resourceSettingsPane.showDiagnostics();
    }


    private void refreshResourceSettings(){if(resourceSettingsPane!=null)resourceSettingsPane.refresh();}
    private boolean pluginOperationRunning(){return modelPluginLibrary!=null&&modelPluginLibrary.busy();}
    private void acceptPluginLibrarySnapshot(ModelPluginStore.Snapshot snapshot){
        if(shuttingDown)return;
        var selection=snapshot.selection();boolean executionChanged=!modelPluginExecutions.equals(snapshot.executions());
        boolean changed=executionChanged||!selection.rules().fingerprint().equals(modelPluginSelection.rules().fingerprint());
        if(viewerCoordinator!=null)viewerCoordinator.setModelRules(selection.rules(),executionChanged);
        modelPluginExecutions=snapshot.executions();modelPlugins=snapshot.catalog();modelPluginSelection=selection;
        if(resourceSettingsPane!=null)resourceSettingsPane.replacePlugins(modelPlugins,selection.enabled());
        if(changed){viewerCacheInspectionId++;updateViewerAreaList(List.of());viewport.invalidateViewerLoadedSelection();invalidateResourceDiagnostics("解析插件已改变，重新生成后显示资源诊断");if(viewerLockedSelection!=null)viewerConfirmButton.setText("生成三维预览");refreshViewerCachedAreas();}
        refreshResourceSettings();
    }


    private static String resourceDiagnostics(ViewerTaskResult result) {
        return ResourceDiagnosticReport.from(result).toText();
    }
    private void invalidateResourceDiagnostics(String explanation){
        viewerResourceDiagnostic=explanation;viewerDiagnosticReport=ResourceDiagnosticReport.empty(explanation);
    }
    private void exportResourceDiagnostics(){
        var report=viewerDiagnosticReport;if(!report.generated())return;
        var chooser=new FileChooser();chooser.setTitle("导出当前选区解析诊断");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("解析诊断 JSON","*.json"));
        chooser.setInitialFileName("mcwe-diagnostics-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+".json");
        Path exports=PortablePaths.exportsDirectory();if(java.nio.file.Files.isDirectory(exports))chooser.setInitialDirectory(exports.toFile());
        File selected=chooser.showSaveDialog(resourceSettingsWindow());if(selected==null)return;
        var task=new Task<Path>(){
            @Override protected Path call()throws IOException{
                Path target=selected.toPath().toAbsolutePath().normalize();
                Path temporary=java.nio.file.Files.createTempFile(target.getParent(),".mcwe-diagnostics-",".tmp");
                try{
                    java.nio.file.Files.writeString(temporary,report.toJson(),java.nio.charset.StandardCharsets.UTF_8);
                    try{java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
                    catch(java.nio.file.AtomicMoveNotSupportedException unavailable){java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
                    return target;
                }finally{java.nio.file.Files.deleteIfExists(temporary);}
            }
        };
        task.setOnSucceeded(event->statusLabel.setText("解析诊断已导出："+task.getValue()));
        task.setOnFailed(event->{LOGGER.warn("解析诊断导出失败",task.getException());statusLabel.setText("解析诊断导出失败："+shortMessage(task.getException()));});
        var thread=new Thread(task,"resource-diagnostics-export");thread.setDaemon(true);thread.start();
    }

    private List<Path> defaultClientResourceRoots() {
        List<Path> roots = new ArrayList<>();
        try { portableSettings.loadCustomSavesPath().ifPresent(roots::add); }
        catch (IOException e) { LOGGER.warn("无法读取游戏目录配置", e); }
        roots.add(PortablePaths.applicationRoot().resolve("MC"));
        roots.add(WorldScanner.getDefaultGameRoot());
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) roots.add(Path.of(appdata, ".minecraft"));
        return List.copyOf(roots);
    }

    private void cancelViewerResourceLookup() {
        viewerResourceLookupId++;
        if (viewerResourceLookup != null) viewerResourceLookup.cancel(true);
        viewerResourceLookup = null;
    }

    private void startViewerResourceLookup(Path explicit) {
        cancelViewerResourceLookup();
        if (world == null || !world.isParsed() || shuttingDown) return;
        long lookupId = viewerResourceLookupId;
        Path worldPath = world.getFolderPath().toAbsolutePath().normalize();
        String version = world.getVersionName();
        viewerTextureMenu.setText(explicit == null ? "纹理：查找中" : "纹理：校验中");
        viewerTextureMenu.setTooltip(new Tooltip("正在检查本地客户端资源，不会下载或修改存档"));
        viewerConfirmButton.setDisable(true);
        Task<ClientResourceLocator.Result> task = new Task<>() {
            @Override protected ClientResourceLocator.Result call() throws Exception {
                if (explicit != null && ClientResourceLocator.isValidVersion(version)) {
                    try (var archive = ClientResourceArchive.open(explicit, version)) {
                        if (isCancelled()) throw new java.util.concurrent.CancellationException();
                        Path selected = archive.fingerprint().path();
                        String message = "已选择本地原版资源";
                        return new ClientResourceLocator.Result(ClientResourceLocator.Status.FOUND,
                                java.util.Optional.of(selected), message, 1);
                    }
                }
                java.util.Optional<Path> binding;
                try { binding = portableSettings.loadClientResourcePath(worldPath, version); }
                catch (IOException e) { LOGGER.warn("忽略无效资源绑定", e); binding = java.util.Optional.empty(); }
                return new ClientResourceLocator().locate(worldPath, version, binding, clientResourceRoots.get(), this::isCancelled);
            }
        };
        viewerResourceLookup = task;
        task.setOnSucceeded(event -> {
            if (lookupId != viewerResourceLookupId || shuttingDown || world == null
                    || !world.getFolderPath().toAbsolutePath().normalize().equals(worldPath)) return;
            viewerResourceLookup = null;
            var result = task.getValue();
            String message=result.message();
            if(explicit!=null&&result.path().isPresent()) {
                try{portableSettings.saveClientResourcePath(worldPath,version,result.path().orElseThrow());message+="，并记住该存档与版本的绑定";}
                catch(IOException failure){LOGGER.warn("资源绑定保存失败",failure);message+="，但绑定保存失败："+shortMessage(failure);}
            }
            setViewerResource(result.path().orElse(null));
            if (result.path().isPresent() && explicit == null) viewerTextureMenu.setText("纹理：自动 · " + version);
            viewerTextureMenu.setTooltip(new Tooltip(message + result.path().map(path -> "\n" + path).orElse("")));
            if (viewerLockedSelection == null && viewport.isViewerSelectionMode()) viewerSelectionLabel.setText(message.split("\n")[0]);
            refreshViewerCachedAreas();
            refreshResourceSettings();
        });
        task.setOnFailed(event -> {
            if (lookupId != viewerResourceLookupId || shuttingDown) return;
            viewerResourceLookup = null;
            setViewerResource(null);
            viewerTextureMenu.setTooltip(new Tooltip("资源校验失败：" + shortMessage(task.getException()) + "\n可以重新选择或使用简化颜色"));
            viewerSelectionLabel.setText("资源校验失败：" + shortMessage(task.getException()));
            refreshResourceSettings();
        });
        Thread thread = new Thread(task, "client-resource-locator");
        thread.setDaemon(true); thread.start();
    }

    private void setViewerResource(Path path) {
        boolean changed = !Objects.equals(viewerResourcePath, path);
        viewerResourcePath = path;
        if(changed)invalidateResourceDiagnostics("资源已改变，重新生成后查看解析诊断。");
        if (viewerCoordinator != null) viewerCoordinator.setResource(path, world.getVersionName(),viewerResourceInstance);
        viewerCacheInspectionId++;
        if (changed) {
            updateViewerAreaList(List.of());
            viewport.invalidateViewerLoadedSelection();
        }
        viewerTextureMenu.setText(path == null ? "纹理：简化颜色" : "纹理：原版 " + world.getVersionName());
        viewerTextureMenu.setTooltip(new Tooltip(path == null ? "所有方块使用简化颜色"
                : path + "\n生成时校验资源；按选区实际状态解析；按通用模型与可用存档颜色生成；未支持内容会给出诊断。"));
        if (viewerLockedSelection != null) viewerConfirmButton.setText("生成三维预览");
        viewerConfirmButton.setDisable(viewerTaskRunning || viewerResourceLookup != null || viewerLockedSelection == null);
        viewerSelectionLabel.setText(path == null ? "已切换简化颜色，可重新生成三维区域"
                : "已选择客户端资源，可生成三维预览");
        refreshResourceSettings();
    }

    private void startViewerTask() {
        if(pluginOperationRunning()){viewerSelectionLabel.setText("正在处理解析插件，完成后可生成预览");return;}
        if(modelPluginLibrary!=null&&modelPluginLibrary.needsRestart()){modelPluginLibrary.recoverForPreview(this::startViewerTask);return;}
        if (viewerTaskRunning || viewerResourceLookup != null || viewerLockedSelection == null
                || world == null || dimension == null || layer == null) {
            return;
        }
        invalidateResourceDiagnostics("正在生成新选区，完成后显示解析诊断。");
        ChunkRectangle target = viewerLockedSelection;
        MapViewportState mapState = viewport.viewportState();
        ViewerRequest request = new ViewerRequest(
                world.getFolderPath(),
                world.getLevelName(),
                dimension.id(),
                dimension.displayName(),
                target,
                viewerProfile,
                new ViewerMapReturnState(
                        mapState.centerX(), mapState.centerZ(),
                        mapState.visualBlocksPerPixel(), layer));
        activeViewerTarget = target;
        activeViewerExpectedFromCache = isCachedArea(target);
        setViewerTaskUi(true);
        viewerSelectionLabel.setText("正在准备三维预览");
        if (activeViewerExpectedFromCache) {
            viewerProgressBar.setManaged(false);
            viewerProgressBar.setVisible(false);
            viewerSelectionLabel.setText("正在打开缓存中的三维预览");
        }
        try {
            activeViewerRequestId = ensureViewerCoordinator().start(
                    request,
                    new ViewerTaskListener() {
                        @Override
                        public void onProgress(long requestId, ViewerProgress progress) {
                            Platform.runLater(() -> acceptViewerProgress(requestId, progress));
                        }

                        @Override
                        public void onSucceeded(long requestId, ViewerTaskResult result) {
                            var diagnostics = new Task<ResourceDiagnosticReport>() {
                                @Override protected ResourceDiagnosticReport call() {
                                    return ResourceDiagnosticReport.from(result);
                                }
                            };
                            diagnostics.setOnSucceeded(event -> acceptViewerSuccess(requestId, result, diagnostics.getValue()));
                            diagnostics.setOnFailed(event -> {
                                LOGGER.warn("Failed to prepare resource diagnostics", diagnostics.getException());
                                acceptViewerSuccess(requestId, result, ResourceDiagnosticReport.empty("预览已生成，但解析报告整理失败；请重新生成后重试。"));
                            });
                            var thread = new Thread(diagnostics, "resource-diagnostics-prepare");
                            thread.setDaemon(true);
                            thread.start();
                        }

                        @Override
                        public void onFailed(long requestId, Throwable failure) {
                            Platform.runLater(() -> acceptViewerFailure(requestId, failure));
                        }

                        @Override
                        public void onCancelled(long requestId) {
                            Platform.runLater(() -> acceptViewerCancellation(requestId));
                        }
                    });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to start voxel preview", e);
            activeViewerRequestId = -1;
            viewerTaskRunning = false;
            invalidateResourceDiagnostics("三维预览启动失败：" + shortMessage(e));
            setViewerTaskUi(false);
            restoreActiveViewerTarget();
            viewerSelectionLabel.setText("三维预览启动失败：" + shortMessage(e));
        }
    }

    private void acceptViewerProgress(long requestId, ViewerProgress progress) {
        if (requestId != activeViewerRequestId || !viewerTaskRunning) {
            return;
        }
        if (activeViewerExpectedFromCache
                && progress.stage() != com.mcworldexplorer.viewer.ViewerProgressStage.READING_CHUNKS
                && progress.stage() != com.mcworldexplorer.viewer.ViewerProgressStage.BUILDING_MESH) {
            return;
        }
        viewerProgressBar.setManaged(true);
        viewerProgressBar.setVisible(true);
        viewerSelectionLabel.setText(progress.message());
        viewerProgressBar.setProgress(progress.fraction());
    }

    private void acceptViewerSuccess(long requestId, ViewerTaskResult result) {
        acceptViewerSuccess(requestId, result, ResourceDiagnosticReport.from(result));
    }

    private void acceptViewerSuccess(long requestId, ViewerTaskResult result, ResourceDiagnosticReport report) {
        if (requestId != activeViewerRequestId) {
            return;
        }
        viewport.markViewerSelectionLoaded();
        viewerDiagnosticReport=report;
        viewerResourceDiagnostic=report.summary()+"\n"+String.join("\n",report.contextDetails());
        refreshResourceSettings();
        activeViewerRequestId = -1;
        viewerTaskRunning = false;
        activeViewerTarget = null;
        activeViewerExpectedFromCache = false;
        setViewerTaskUi(false);
        refreshViewerCachedAreas();
        setViewerAreasVisible(true);
        long fallbackBlocks = result.snapshot().warnings().stream()
                .filter(w -> w.code() == MeshWarning.Code.TEXTURE_FALLBACK)
                .mapToLong(MeshWarning::occurrences).sum();
        String state = result.fromCache() ? "已打开缓存中的三维预览" : "三维预览已打开";
        if (result.snapshot().atlas().isPresent() && viewerTextureMenu != null) {
            String details = result.snapshot().warnings().stream()
                    .filter(w -> w.code() == MeshWarning.Code.TEXTURE_FALLBACK || w.code() == MeshWarning.Code.MODEL_APPROXIMATION || w.code()==MeshWarning.Code.RESOURCE_WARNING)
                    .limit(8).map(MeshWarning::detail)
                    .collect(java.util.stream.Collectors.joining("\n"));
            viewerTextureMenu.setTooltip(new Tooltip(viewerResourcePath
                    + "\n"+result.snapshot().warnings().stream().filter(w->w.code()==MeshWarning.Code.RESOURCE_CONTEXT).map(MeshWarning::detail).findFirst().orElse("")
                    + "\n按选区模型与可用存档颜色显示；未支持的行为会记录诊断。"
                    + "\n静态外观不包含存档未保存的屏幕画面等运行时内容。"
                    + (details.isEmpty() ? "" : "\n本次近似或简化显示说明（最多 8 项）：\n" + details)));
        }
        if (result.snapshot().atlas().isPresent()) state += "，资源纹理" + (fallbackBlocks > 0 ? "（" + fallbackBlocks + " 个方块使用简化颜色）" : "");
        if (result.partialSuccess()) state += "，" + result.failedChunks() + " 个区块读取失败";
        if (viewerLockedSelection != null) {
            lockViewerSelection(viewerLockedSelection);
            viewerSelectionLabel.setText(state + "；" + formatViewerSelection(viewerLockedSelection));
        } else {
            clearViewerSelection(state);
        }
    }

    private void acceptViewerFailure(long requestId, Throwable failure) {
        if (requestId != activeViewerRequestId) {
            return;
        }
        LOGGER.error("Voxel preview generation failed", failure);
        invalidateResourceDiagnostics("本次生成失败，未取得完整解析报告："+shortMessage(failure));
        activeViewerRequestId = -1;
        viewerTaskRunning = false;
        activeViewerExpectedFromCache = false;
        setViewerTaskUi(false);
        restoreActiveViewerTarget();
        viewerSelectionLabel.setText("生成失败：" + shortMessage(failure));
        viewerConfirmButton.setText("重新生成");
    }

    private void acceptViewerCancellation(long requestId) {
        if (requestId != activeViewerRequestId) {
            return;
        }
        activeViewerRequestId = -1;
        viewerTaskRunning = false;
        activeViewerExpectedFromCache = false;
        setViewerTaskUi(false);
        clearViewerSelection("已取消生成；" + viewerSelectionHint());
        invalidateResourceDiagnostics("本次生成已取消，未取得新的解析报告。");
        refreshResourceSettings();
    }

    private void cancelViewerTask(boolean restoreSelection) {
        if (!viewerTaskRunning) {
            return;
        }
        long cancelledId = activeViewerRequestId;
        activeViewerRequestId = -1;
        viewerTaskRunning = false;
        activeViewerExpectedFromCache = false;
        if (viewerCoordinator != null) {
            viewerCoordinator.cancel();
        }
        invalidateResourceDiagnostics("本次生成已取消，未取得新的解析报告。");
        setViewerTaskUi(false);
        if (restoreSelection) {
            restoreActiveViewerTarget();
            viewerSelectionLabel.setText("已取消生成");
        } else {
            activeViewerTarget = null;
        }
        refreshResourceSettings();
        LOGGER.debug("Cancelled voxel preview request {}", cancelledId);
    }

    private void restoreActiveViewerTarget() {
        if (activeViewerTarget == null) {
            return;
        }
        if (viewerLockedSelection == null) viewerLockedSelection = activeViewerTarget;
        viewport.lockViewerSelection(viewerLockedSelection);
        viewerConfirmButton.setDisable(viewerResourceLookup != null);
        activeViewerTarget = null;
    }

    private void setViewerTaskUi(boolean running) {
        if (viewerTextureMenu != null) viewerTextureMenu.setDisable(running);
        viewerTaskRunning = running;
        if (viewport != null) viewport.setViewerTaskRunning(running);
        updateViewerSelectionActions();
        refreshResourceSettings();
        if (viewerAreaList != null) viewerAreaList.setAreas(viewerCachedPreviews, viewerProfile, running);
        if (viewerProgressBar == null) {
            return;
        }
        viewerProgressBar.setManaged(running);
        viewerProgressBar.setVisible(running);
        viewerProgressBar.setProgress(-1);
    }

    private void updateViewerSelectionActions() {
        boolean canCancel = viewerTaskRunning || viewerLockedSelection != null;
        if (viewerRangeComboBox != null) viewerRangeComboBox.setDisable(canCancel);
        if (viewerProfileComboBox != null) viewerProfileComboBox.setDisable(canCancel);
        if (viewerCancelButton != null) {
            viewerCancelButton.setManaged(canCancel);
            viewerCancelButton.setVisible(canCancel);
            viewerCancelButton.setText(viewerTaskRunning ? "取消生成" : "取消选区");
        }
        if (viewerConfirmButton != null) {
            viewerConfirmButton.setDisable(viewerTaskRunning || viewerResourceLookup != null || viewerLockedSelection == null);
        }
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
        tileLoadTracker.clear();
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
            locateMenuButton.hide();
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
        moreMenuButton.hide();
        if (pluginOperationRunning()) {
            statusLabel.setText("解析插件操作结束后可清理缓存");
            return;
        }
        if (world == null) {
            return;
        }
        WorldInfo target = world;
        long inspectionId = ++cacheMaintenanceId;
        clearCacheButton.setDisable(true);
        statusLabel.setText("正在统计当前存档缓存...");
        Task<WorldMapCacheCleaner.Summary> task = new Task<>() {
            @Override
            protected WorldMapCacheCleaner.Summary call() throws IOException {
                return cacheCleaner.inspect(target);
            }
        };
        task.setOnSucceeded(done -> {
            if (world != target || inspectionId != cacheMaintenanceId) {
                return;
            }
            Alert alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    cacheConfirmationText(target.getLevelName(), task.getValue()),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            alert.initOwner(viewportHost.getScene().getWindow());
            alert.setTitle("清理存档缓存并重新加载");
            alert.setHeaderText("确认清理当前存档缓存？");
            if (alert.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
                clearCacheButton.setDisable(false);
                updateStatus();
                return;
            }
            clearWorldCache(target);
        });
        task.setOnFailed(failed -> {
            LOGGER.error("Failed to inspect cache for {}", target.getFolderPath(), task.getException());
            if (world == target && inspectionId == cacheMaintenanceId) {
                clearCacheButton.setDisable(false);
                statusLabel.setText("缓存统计失败：" + shortMessage(task.getException()));
            }
        });
        Thread thread = new Thread(task, "map-cache-inspector");
        thread.setDaemon(true);
        thread.start();
    }

    private void clearWorldCache(WorldInfo target) {
        if (pluginOperationRunning()) {
            clearCacheButton.setDisable(false);
            statusLabel.setText("解析插件操作结束后可清理缓存");
            return;
        }
        long maintenanceId = ++cacheMaintenanceId;
        contextId++;
        scheduler.cancelAll();
        currentRequestId = -1;
        tileLoadTracker.clear();
        cacheMaintenanceInProgress = true;
        refreshResourceSettings();
        Task<WorldMapCacheCleaner.ClearResult> task = new Task<>() {
            @Override
            protected WorldMapCacheCleaner.ClearResult call() throws IOException {
                return cacheCleaner.clear(target);
            }
        };
        task.setOnSucceeded(done -> {
            if (world != target || maintenanceId != cacheMaintenanceId) {
                return;
            }
            WorldMapCacheCleaner.ClearResult result = task.getValue();
            cacheMaintenanceInProgress = false;
            refreshResourceSettings();
            clearCacheButton.setDisable(false);
            if (result.changed() || !result.hasFailures()) {
                tileService.clearMemory();
                readyKeys.clear();
                viewport.clearTiles();
                statusLabel.setText(cacheClearStatus(result));
                refreshTiles();
                statusLabel.setText(cacheClearStatus(result));
            } else {
                refreshTiles();
                statusLabel.setText("缓存清理失败：" + result.firstFailure());
            }
        });
        task.setOnFailed(failed -> {
            LOGGER.error("Failed to clear cache for {}", target.getFolderPath(), task.getException());
            if (world == target && maintenanceId == cacheMaintenanceId) {
                cacheMaintenanceInProgress = false;
                refreshResourceSettings();
                clearCacheButton.setDisable(false);
                refreshTiles();
                statusLabel.setText("缓存清理失败：" + shortMessage(task.getException()));
            }
        });
        Thread thread = new Thread(task, "map-cache-cleaner");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void handleExport(ActionEvent event) {
        moreMenuButton.hide();
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
        if (cacheMaintenanceInProgress) {
            return;
        }
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
        currentRequestId = request;
        prefetchRequestId = -1;
        long taskContext = contextId;
        for (int index = 0; index < requestedKeys.size(); index++) {
            MapTileKey key = requestedKeys.get(index);
            if (readyKeys.contains(key) || tileLoadTracker.isFailed(key)) {
                continue;
            }
            tileLoadTracker.ensureAutomaticLoading(key);
            submitVisibleTile(
                    taskContext,
                    request,
                    key,
                    index,
                    requestedWorld,
                    requestedDimension,
                    focusX,
                    focusZ);
        }
        syncTileLoadStates();
        updateStatus();
    }

    private void submitVisibleTile(
            long taskContext,
            long request,
            MapTileKey key,
            int priority,
            WorldInfo requestedWorld,
            WorldDimension requestedDimension,
            double focusX,
            double focusZ) {
        scheduler.submit(
                key,
                priority,
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
                        acceptTileFailure(
                                taskContext,
                                request,
                                key,
                                priority,
                                requestedWorld,
                                requestedDimension,
                                focusX,
                                focusZ,
                                failure)));
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
        tileLoadTracker.recordSuccess(key);
        readyKeys.add(key);
        viewport.showTile(key, toFxImage(result.image()), result.markers());
        syncTileLoadStates();
        updateStatus();
        if (readyKeys.size() == requestedKeys.size()
                && tileLoadTracker.failedCount(Set.copyOf(requestedKeys)) == 0) {
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
            int priority,
            WorldInfo requestedWorld,
            WorldDimension requestedDimension,
            double focusX,
            double focusZ,
            Throwable failure) {
        if (taskContext != contextId || !scheduler.isCurrent(request)
                || !requestedKeys.contains(key)) {
            return;
        }
        MapTileLoadTracker.FailureAction action = tileLoadTracker.recordFailure(key);
        if (action == MapTileLoadTracker.FailureAction.RETRY_AUTOMATICALLY) {
            LOGGER.debug("Retrying map tile after initial failure {}", key, failure);
            submitVisibleTile(
                    taskContext,
                    request,
                    key,
                    priority,
                    requestedWorld,
                    requestedDimension,
                    focusX,
                    focusZ);
        } else if (action == MapTileLoadTracker.FailureAction.SHOW_FAILED) {
            LOGGER.warn("Failed to load map tile {}", key, failure);
        }
        syncTileLoadStates();
        updateStatus();
    }

    private void retryTile(MapTileKey key) {
        if (world == null || dimension == null || layer == null
                || currentRequestId < 0
                || !scheduler.isCurrent(currentRequestId)
                || !requestedKeys.contains(key)
                || !tileLoadTracker.beginManualRetry(key)) {
            return;
        }
        int priority = requestedKeys.indexOf(key);
        MapViewportState state = viewport.viewportState();
        syncTileLoadStates();
        updateStatus();
        submitVisibleTile(
                contextId,
                currentRequestId,
                key,
                priority,
                world,
                dimension,
                state.centerX(),
                state.centerZ());
    }

    private void syncTileLoadStates() {
        Set<MapTileKey> requested = Set.copyOf(requestedKeys);
        viewport.setTileLoadStates(
                tileLoadTracker.failedKeys(requested),
                tileLoadTracker.retryingKeys(requested));
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
        Set<MapTileKey> requested = Set.copyOf(requestedKeys);
        int failedTiles = tileLoadTracker.failedCount(requested);
        String loadingState = viewport.isShowingTemporaryScale()
                ? "临时缩放画面"
                : failedTiles > 0
                        ? "部分图块失败"
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
        int failedTiles = tileLoadTracker.failedCount(Set.copyOf(requestedKeys));
        exportButton.setDisable(world == null
                || requestedKeys.isEmpty()
                || readyKeys.size() != requestedKeys.size()
                || failedTiles > 0);
    }

    private List<DisplayMapMarker> fixedMarkers() {
        if (world == null || dimension == null) {
            return List.of();
        }
        List<DisplayMapMarker> markers = new ArrayList<>();
        for (PlayerNavigation player : playerNavigations()) {
            if (player.dimensionId().equals(dimension.id())) {
                markers.add(DisplayMapMarker.player(
                        MapMarker.point(
                                MapMarkerType.PLAYER,
                                dimension.id(),
                                floorToInt(player.x()),
                                floorToInt(player.y()),
                                floorToInt(player.z())),
                        player.identifier(),
                        player.displayName(),
                        dimension.displayName(),
                        player.x(),
                        player.y(),
                        player.z()));
            }
        }
        if (dimension.isOverworld() && world.isSpawnPositionAvailable()) {
            markers.add(DisplayMapMarker.standard(MapMarker.point(
                    MapMarkerType.WORLD_SPAWN,
                    dimension.id(),
                    world.getSpawnX(),
                    world.getSpawnY(),
                    world.getSpawnZ())));
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

    static String cacheConfirmationText(
            String worldName,
            WorldMapCacheCleaner.Summary summary) {
        String displayName = worldName == null || worldName.isBlank()
                ? "当前存档"
                : "“" + worldName + "”";
        return displayName + "包含 "
                + summary.fileCount() + " 个缓存文件，共 "
                + formatBytes(summary.bytes())
                + "。\n\n将删除静态预览和全部地图块缓存，随后重新加载当前视口。"
                + "不会修改存档，也不会影响其他世界。";
    }

    static String cacheClearStatus(WorldMapCacheCleaner.ClearResult result) {
        if (!result.changed() && !result.hasFailures()) {
            return "没有可清理的缓存，正在重新加载";
        }
        String status = "已清理 " + result.deletedFiles()
                + " 个文件，释放 " + formatBytes(result.deletedBytes());
        if (result.hasFailures()) {
            status += "，" + result.failedEntries()
                    + " 项未能清理：" + result.firstFailure();
        }
        return status + "，正在重新加载";
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
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
