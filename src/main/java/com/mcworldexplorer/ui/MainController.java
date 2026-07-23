package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.PreviewCache;
import com.mcworldexplorer.preview.PreviewCacheResult;
import com.mcworldexplorer.preview.DimensionHeightRange;
import com.mcworldexplorer.preview.DimensionHeightResolver;
import com.mcworldexplorer.preview.PreviewGenerationMonitor;
import com.mcworldexplorer.preview.PreviewGenerationResult;
import com.mcworldexplorer.preview.PreviewGenerator;
import com.mcworldexplorer.preview.PreviewExporter;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.PreviewRequest;
import com.mcworldexplorer.preview.PreviewRequestResolver;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.preview.PreviewExporter.ExportException;
import com.mcworldexplorer.preview.PreviewExporter.FailureReason;
import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

public class MainController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainController.class);
    private static final String UNKNOWN = "Unknown";
    private static final String NOT_AVAILABLE = "-";
    private static final String NO_SELECTION = "Select a World";
    private static final String PARSE_FAILED = "解析失败";
    private static final String CHOOSE_FOLDER_TITLE = "Select Minecraft Folder";
    private static final String SCANNING = "Scanning...";
    private static final String NO_WORLDS = "No worlds found";
    private static final String SCAN_FAILED = "Scan failed";
    private static final String FOLDER_NOT_FOUND = "Folder not found";
    private static final String PREVIEW_NO_SELECTION = "选择存档后生成缩略图";
    private static final String PREVIEW_UNAVAILABLE = "该存档无法生成缩略图";
    private static final String PREVIEW_GENERATING = "正在生成缩略图...";
    private static final String EXPORT_NO_PREVIEW = "当前没有可导出的缩略图";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final PreviewGenerator previewGenerator = new PreviewGenerator();
    private final PreviewCache previewCache = new PreviewCache();
    private final PreviewExporter previewExporter = new PreviewExporter();
    private final PortableSettings portableSettings = new PortableSettings();
    private Task<PreviewDisplay> previewTask;
    private PreviewExportSource previewExportSource;
    private WorldInfo previewWorld;
    private Path selectedRootPath;
    private String pendingScanWarning;
    private long previewRequestId;
    private boolean updatingPreviewControls;
    private final DimensionPreviewStateStore dimensionPreviewStates = new DimensionPreviewStateStore();

    @FXML
    private TreeView<WorldTreeNode> worldTreeView;

    @FXML
    private Button chooseFolderButton;

    @FXML
    private ProgressIndicator scanProgressIndicator;

    @FXML
    private Label scanStatusLabel;

    @FXML
    private Label worldNameLabel;

    @FXML
    private Label versionLabel;

    @FXML
    private Label gameModeLabel;

    @FXML
    private Label folderCreationTimeLabel;

    @FXML
    private Label lastPlayedLabel;

    @FXML
    private Label gameTimeLabel;

    @FXML
    private Label seedLabel;

    @FXML
    private Label spawnPosLabel;

    @FXML
    private Label playerPosLabel;

    @FXML
    private TabPane detailTabPane;

    @FXML
    private Tab previewTab;

    @FXML
    private ImageView previewImageView;

    @FXML
    private StackPane previewSurfacePane;

    @FXML
    private Label previewPlaceholderLabel;

    @FXML
    private Label previewStatusLabel;

    @FXML
    private ProgressBar previewProgressBar;

    @FXML
    private Button exportPreviewButton;

    @FXML
    private ComboBox<WorldDimension> dimensionComboBox;

    @FXML
    private ToggleButton surfaceOverviewButton;

    @FXML
    private Slider layerHeightSlider;

    @FXML
    private Label layerRangeLabel;

    @FXML
    public void initialize() {
        previewSurfacePane.widthProperty().addListener((observable, oldValue, newValue) -> updatePreviewImageSize());
        previewSurfacePane.heightProperty().addListener((observable, oldValue, newValue) -> updatePreviewImageSize());
        dimensionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingPreviewControls && previewWorld != null && newValue != null) {
                selectPreviewDimension(previewWorld, newValue);
            }
        });
        layerHeightSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingPreviewControls) {
                updateSliderPosition(newValue.doubleValue());
                if (!layerHeightSlider.isValueChanging()) {
                    activateSliderLayer();
                }
            }
        });
        layerHeightSlider.valueChangingProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingPreviewControls && oldValue && !newValue) {
                activateSliderLayer();
            }
        });
        clearDetails();

        // Setup custom cell factory for icons and names
        worldTreeView.setCellFactory(treeView -> new WorldTreeCell());

        // Listen for selection changes
        worldTreeView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && newValue.getValue().getWorldInfo() != null) {
                        WorldInfo world = newValue.getValue().getWorldInfo();
                        showWorldDetails(world);
                        preparePreview(world);
                    } else {
                        clearDetails();
                    }
                }
        );

        loadWorlds();
    }

    private void loadWorlds() {
        Path rootPath;
        try {
            rootPath = portableSettings.loadCustomSavesPath()
                    .orElseGet(WorldScanner::getDefaultGameRoot);
        } catch (IOException e) {
            LOGGER.warn("Failed to read portable settings", e);
            pendingScanWarning = "本地配置读取失败，已使用默认目录";
            rootPath = WorldScanner.getDefaultGameRoot();
        }
        selectedRootPath = rootPath;

        if (rootPath == null || !Files.isDirectory(rootPath)) {
            showWorlds(new LinkedHashMap<>());
            setScanState(false, withScanWarning(FOLDER_NOT_FOUND));
            return;
        }

        startWorldScan(rootPath);
    }

    private void startWorldScan(Path rootPath) {
        setScanState(true, SCANNING);
        clearDetails();

        Task<Map<String, List<WorldInfo>>> scanTask = new Task<>() {
            @Override
            protected Map<String, List<WorldInfo>> call() {
                return WorldScanner.scanSelectedPath(rootPath);
            }
        };

        scanTask.setOnSucceeded(event -> {
            Map<String, List<WorldInfo>> groupedWorlds = scanTask.getValue();
            showWorlds(groupedWorlds);
            int worldCount = countWorlds(groupedWorlds);
            setScanState(false, withScanWarning(worldCount == 0 ? NO_WORLDS : worldCount + " worlds"));
        });
        scanTask.setOnFailed(event -> {
            LOGGER.error("Failed to scan selected Minecraft folder {}", rootPath, scanTask.getException());
            showWorlds(new LinkedHashMap<>());
            setScanState(false, withScanWarning(SCAN_FAILED));
        });

        Thread scanThread = new Thread(scanTask, "world-scanner");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void showWorlds(Map<String, List<WorldInfo>> groupedWorlds) {
        TreeItem<WorldTreeNode> rootItem = new TreeItem<>(WorldTreeNode.group("Root"));
        rootItem.setExpanded(true);
        for (Map.Entry<String, List<WorldInfo>> entry : groupedWorlds.entrySet()) {
            TreeItem<WorldTreeNode> groupItem = new TreeItem<>(WorldTreeNode.group(entry.getKey()));
            groupItem.setExpanded(true);
            for (WorldInfo info : entry.getValue()) {
                groupItem.getChildren().add(new TreeItem<>(WorldTreeNode.world(info)));
            }
            rootItem.getChildren().add(groupItem);
        }

        worldTreeView.setRoot(rootItem);
        worldTreeView.setShowRoot(false);
    }

    private void setScanState(boolean scanning, String status) {
        chooseFolderButton.setDisable(scanning);
        scanProgressIndicator.setManaged(scanning);
        scanProgressIndicator.setVisible(scanning);
        scanStatusLabel.setText(status);
    }

    private String withScanWarning(String status) {
        if (pendingScanWarning == null) {
            return status;
        }
        String combined = status + " · " + pendingScanWarning;
        pendingScanWarning = null;
        return combined;
    }

    static int countWorlds(Map<String, List<WorldInfo>> groupedWorlds) {
        return groupedWorlds.values().stream().mapToInt(List::size).sum();
    }

    @FXML
    public void handleChooseFolder(ActionEvent event) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(CHOOSE_FOLDER_TITLE);

        if (selectedRootPath != null) {
            File currentDir = selectedRootPath.toFile();
            if (currentDir.exists() && currentDir.isDirectory()) {
                chooser.setInitialDirectory(currentDir);
            }
        }
        
        File selectedDir = chooser.showDialog(worldTreeView.getScene().getWindow());
        if (selectedDir != null) {
            selectedRootPath = selectedDir.toPath().toAbsolutePath().normalize();
            try {
                portableSettings.saveCustomSavesPath(selectedRootPath);
            } catch (IOException e) {
                LOGGER.error("Failed to save portable settings", e);
                pendingScanWarning = "目录可用，但无法保存到本地配置";
            }
            startWorldScan(selectedRootPath);
        }
    }

    private void showWorldDetails(WorldInfo info) {
        if (info == null) {
            clearDetails();
            return;
        }

        worldNameLabel.setText(info.getLevelName());
        versionLabel.setText(info.getVersionName());
        
        if (!info.isParsed()) {
            gameModeLabel.setText(PARSE_FAILED);
            folderCreationTimeLabel.setText(PARSE_FAILED);
            lastPlayedLabel.setText(PARSE_FAILED);
            gameTimeLabel.setText(PARSE_FAILED);
            seedLabel.setText(PARSE_FAILED);
            spawnPosLabel.setText(PARSE_FAILED);
            playerPosLabel.setText(PARSE_FAILED);
            return;
        }

        String modeStr = info.getGameType().getDisplayName();
        if (info.isHardcore()) {
            modeStr += " (Hardcore)";
        }
        gameModeLabel.setText(modeStr);

        if (info.isFolderCreationTimeAvailable()) {
            folderCreationTimeLabel.setText(DATE_FORMAT.format(new Date(info.getFolderCreationTime())));
        } else {
            folderCreationTimeLabel.setText(UNKNOWN);
        }

        // Format Date
        if (info.getLastPlayed() > 0) {
            lastPlayedLabel.setText(DATE_FORMAT.format(new Date(info.getLastPlayed())));
        } else {
            lastPlayedLabel.setText(UNKNOWN);
        }

        gameTimeLabel.setText(formatGameTime(info.getGameTime()));

        seedLabel.setText(info.isSeedAvailable() ? String.valueOf(info.getRandomSeed()) : UNKNOWN);

        if (info.isSpawnPositionAvailable()) {
            spawnPosLabel.setText(String.format("%d, %d, %d", info.getSpawnX(), info.getSpawnY(), info.getSpawnZ()));
        } else {
            spawnPosLabel.setText(UNKNOWN);
        }

        if (info.isPlayerPositionAvailable()) {
            String pos = String.format("%.1f, %.1f, %.1f", info.getPlayerX(), info.getPlayerY(), info.getPlayerZ());
            playerPosLabel.setText(pos);
        } else {
            playerPosLabel.setText(UNKNOWN);
        }
    }

    private void preparePreview(WorldInfo world) {
        cancelPreviewTask();
        clearExportState();
        previewImageView.setImage(null);
        previewPlaceholderLabel.setVisible(true);
        detailTabPane.getSelectionModel().select(previewTab);
        previewWorld = world;
        dimensionPreviewStates.clear();
        clearPreviewControls();

        if (!world.isParsed()) {
            setPreviewIdle(PREVIEW_UNAVAILABLE);
            return;
        }

        try {
            List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(world);
            updatingPreviewControls = true;
            dimensionComboBox.setItems(FXCollections.observableArrayList(dimensions));
            dimensionComboBox.getSelectionModel().selectFirst();
            dimensionComboBox.setDisable(dimensions.isEmpty());
            updatingPreviewControls = false;
            WorldDimension initialDimension = dimensionComboBox.getValue();
            if (initialDimension == null) {
                setPreviewIdle("该存档没有可预览维度");
                return;
            }
            selectPreviewDimension(world, initialDimension);
        } catch (IOException e) {
            LOGGER.error("Failed to discover dimensions for {}", world.getFolderPath(), e);
            showPreviewFailure(shortMessage(e));
        }
    }

    private void selectPreviewDimension(WorldInfo world, WorldDimension dimension) {
        DimensionPreviewState state = dimensionPreviewStates.get(dimension);
        if (state == null) {
            clearLayerControls();
            startPreview(world, dimension, null, null);
        } else {
            updateLayerControls(state);
            startPreview(world, dimension, state.selectedLayer(), state.sliderY());
        }
    }

    private void startPreview(
            WorldInfo world,
            WorldDimension dimension,
            PreviewLayer requestedLayer,
            Integer preferredSliderY) {
        cancelPreviewTask();
        clearExportState();
        previewImageView.setImage(null);
        previewPlaceholderLabel.setVisible(true);
        setLayerControlsDisabled(true);

        long requestId = previewRequestId;
        PreviewTask task = new PreviewTask(world, dimension, requestedLayer, preferredSliderY);
        previewTask = task;
        previewStatusLabel.setText(PREVIEW_GENERATING);
        previewPlaceholderLabel.setText(PREVIEW_GENERATING);
        previewProgressBar.progressProperty().unbind();
        previewProgressBar.progressProperty().bind(task.progressProperty());
        previewProgressBar.setManaged(true);
        previewProgressBar.setVisible(true);

        task.setOnSucceeded(event -> {
            if (!isCurrentPreview(task, requestId)) {
                return;
            }
            PreviewDisplay display = task.getValue();
            int sliderY = resolveSliderY(
                    world,
                    dimension,
                    display.heightRange(),
                    display.request().layer(),
                    task.preferredSliderY);
            DimensionPreviewState state = new DimensionPreviewState(
                    display.heightRange(),
                    sliderY,
                    display.request().layer());
            if (showPreviewImage(display.cache().imagePath())) {
                dimensionPreviewStates.put(dimension, state);
                updateLayerControls(state);
                setExportSource(display.cache().imagePath(), world);
                setPreviewReady(display.generation() == null
                        ? formatCachedPreviewStatus(display.request())
                        : formatPreviewStatus(display.generation(), display.request()));
            } else {
                DimensionPreviewState previousState = dimensionPreviewStates.get(dimension);
                if (previousState == null) {
                    clearLayerControls();
                } else {
                    updateLayerControls(previousState);
                }
                showPreviewFailure("缓存图片无法读取");
            }
        });
        task.setOnFailed(event -> {
            if (!isCurrentPreview(task, requestId)) {
                return;
            }
            Throwable failure = task.getException();
            LOGGER.error("Failed to generate preview for {}", world.getFolderPath(), failure);
            DimensionPreviewState state = dimensionPreviewStates.get(dimension);
            if (state == null) {
                clearLayerControls();
            } else {
                updateLayerControls(state);
            }
            showPreviewFailure(shortMessage(failure));
        });
        task.setOnCancelled(event -> {
            if (isCurrentPreview(task, requestId)) {
                finishPreviewProgress();
            }
        });

        Thread thread = new Thread(task, "world-preview-" + requestId);
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    public void handleSurfaceOverview(ActionEvent event) {
        if (updatingPreviewControls) {
            return;
        }
        if (!surfaceOverviewButton.isSelected()) {
            updatingPreviewControls = true;
            surfaceOverviewButton.setSelected(true);
            updatingPreviewControls = false;
        }

        WorldDimension dimension = dimensionComboBox.getValue();
        if (previewWorld == null || dimension == null) {
            return;
        }
        DimensionPreviewState state = dimensionPreviewStates.get(dimension);
        if (state == null) {
            return;
        }
        PreviewLayer surface = PreviewLayer.surfaceOverview();
        startPreview(previewWorld, dimension, surface, state.sliderY());
    }

    private void updateSliderPosition(double value) {
        WorldDimension dimension = dimensionComboBox.getValue();
        if (dimension == null) {
            return;
        }
        DimensionPreviewState state = dimensionPreviewStates.get(dimension);
        if (state == null) {
            return;
        }
        int sliderY = sliderCoordinate(state.heightRange(), value);
        layerRangeLabel.setText(formatLayerSliderLabel(state.heightRange(), sliderY));
        dimensionPreviewStates.put(dimension, state.withSliderY(sliderY));
    }

    private void activateSliderLayer() {
        WorldDimension dimension = dimensionComboBox.getValue();
        if (previewWorld == null || dimension == null) {
            return;
        }
        DimensionPreviewState state = dimensionPreviewStates.get(dimension);
        if (state == null) {
            return;
        }
        int sliderY = sliderCoordinate(state.heightRange(), layerHeightSlider.getValue());
        PreviewLayer layer = layerForSlider(state.heightRange(), sliderY);
        if (shouldSkipLayerRequest(
                layer,
                state.selectedLayer(),
                previewImageView.getImage() != null)) {
            return;
        }

        dimensionPreviewStates.put(dimension, state.withSliderY(sliderY));
        updatingPreviewControls = true;
        surfaceOverviewButton.setSelected(false);
        updatingPreviewControls = false;
        // selectedLayer tracks the last successfully displayed preview.
        startPreview(previewWorld, dimension, layer, sliderY);
    }

    private void updateLayerControls(DimensionPreviewState state) {
        updatingPreviewControls = true;
        surfaceOverviewButton.setSelected(state.selectedLayer().isSurfaceOverview());
        surfaceOverviewButton.setDisable(false);
        layerHeightSlider.setMin(state.heightRange().minY());
        layerHeightSlider.setMax(state.heightRange().maxY());
        layerHeightSlider.setValue(state.sliderY());
        layerHeightSlider.setDisable(false);
        layerRangeLabel.setText(formatLayerSliderLabel(state.heightRange(), state.sliderY()));
        updatingPreviewControls = false;
    }

    private void setLayerControlsDisabled(boolean disabled) {
        surfaceOverviewButton.setDisable(disabled);
        layerHeightSlider.setDisable(disabled);
    }

    private void clearPreviewControls() {
        updatingPreviewControls = true;
        dimensionComboBox.getItems().clear();
        dimensionComboBox.setDisable(true);
        clearLayerControls();
        updatingPreviewControls = false;
    }

    private void clearLayerControls() {
        boolean wasUpdating = updatingPreviewControls;
        updatingPreviewControls = true;
        surfaceOverviewButton.setSelected(false);
        surfaceOverviewButton.setDisable(true);
        layerHeightSlider.setMin(0);
        layerHeightSlider.setMax(0);
        layerHeightSlider.setValue(0);
        layerHeightSlider.setDisable(true);
        layerRangeLabel.setText(NOT_AVAILABLE);
        updatingPreviewControls = wasUpdating;
    }

    private boolean showPreviewImage(Path imagePath) {
        Image image = new Image(imagePath.toUri().toString(), false);
        if (image.isError()) {
            LOGGER.warn("Failed to load preview image {}", imagePath, image.getException());
            return false;
        }
        previewImageView.setImage(image);
        previewPlaceholderLabel.setVisible(false);
        return true;
    }

    private void updatePreviewImageSize() {
        double available = Math.max(0, Math.min(
                PreviewGenerator.OUTPUT_SIZE,
                Math.min(previewSurfacePane.getWidth() - 2, previewSurfacePane.getHeight() - 2)));
        previewImageView.setFitWidth(available);
        previewImageView.setFitHeight(available);
    }

    private void showPreviewFailure(String detail) {
        finishPreviewProgress();
        clearExportState();
        previewImageView.setImage(null);
        previewPlaceholderLabel.setText("缩略图生成失败");
        previewPlaceholderLabel.setVisible(true);
        previewStatusLabel.setText(detail == null || detail.isBlank()
                ? "生成失败，请查看日志"
                : "生成失败：" + detail);
    }

    private void setPreviewIdle(String status) {
        clearExportState();
        finishPreviewProgress();
        previewStatusLabel.setText(status);
        previewPlaceholderLabel.setText(status);
        previewPlaceholderLabel.setVisible(true);
    }

    private void setPreviewReady(String status) {
        finishPreviewProgress();
        previewStatusLabel.setText(status);
        previewPlaceholderLabel.setVisible(
                shouldShowPreviewPlaceholder(previewImageView.getImage() != null));
    }

    static boolean shouldShowPreviewPlaceholder(boolean imagePresent) {
        return !imagePresent;
    }

    static boolean shouldSkipLayerRequest(
            PreviewLayer requestedLayer,
            PreviewLayer selectedLayer,
            boolean imagePresent) {
        return imagePresent && requestedLayer.equals(selectedLayer);
    }

    private void cancelPreviewTask() {
        previewRequestId++;
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
        finishPreviewProgress();
    }

    private boolean isCurrentPreview(Task<PreviewDisplay> task, long requestId) {
        return previewTask == task && previewRequestId == requestId;
    }

    private void finishPreviewProgress() {
        previewProgressBar.progressProperty().unbind();
        previewProgressBar.setManaged(false);
        previewProgressBar.setVisible(false);
    }

    static String formatPreviewStatus(PreviewGenerationResult result) {
        String quality = result.failedChunks() == 0
                ? "已生成"
                : "已生成，" + result.failedChunks() + " 个区块失败";
        return String.format(
                "%s · 中心 %d, %d · %d 个区块",
                quality,
                result.center().x(),
                result.center().z(),
                result.sampledChunks());
    }

    static PreviewLayer layerForSlider(DimensionHeightRange heightRange, double sliderValue) {
        return heightRange.bandContaining(sliderCoordinate(heightRange, sliderValue));
    }

    static int sliderCoordinate(DimensionHeightRange heightRange, double sliderValue) {
        if (heightRange == null) {
            throw new IllegalArgumentException("heightRange must not be null");
        }
        if (!Double.isFinite(sliderValue)) {
            return heightRange.minY();
        }
        long floored = (long) Math.floor(sliderValue);
        return (int) Math.max(heightRange.minY(), Math.min(heightRange.maxY(), floored));
    }

    static String formatLayerSliderLabel(DimensionHeightRange heightRange, double sliderValue) {
        int y = sliderCoordinate(heightRange, sliderValue);
        PreviewLayer layer = heightRange.bandContaining(y);
        return String.format("Y %d · 区间 Y %d - %d", y, layer.minY(), layer.maxY());
    }

    private static int resolveSliderY(
            WorldInfo world,
            WorldDimension dimension,
            DimensionHeightRange heightRange,
            PreviewLayer selectedLayer,
            Integer preferredSliderY) {
        if (preferredSliderY != null) {
            return sliderCoordinate(heightRange, preferredSliderY);
        }
        if (PreviewRequestResolver.playerPositionMatches(world, dimension)) {
            return sliderCoordinate(heightRange, world.getPlayerY());
        }
        if (!selectedLayer.isSurfaceOverview()) {
            if (selectedLayer.minY() <= 64 && selectedLayer.maxY() >= 64) {
                return 64;
            }
            return selectedLayer.minY() + (selectedLayer.maxY() - selectedLayer.minY()) / 2;
        }
        return sliderCoordinate(heightRange, 64);
    }

    static String formatPreviewStatus(
            PreviewGenerationResult result,
            PreviewRequest request) {
        String quality = result.failedChunks() == 0
                ? "已生成"
                : "已生成，" + result.failedChunks() + " 个区块失败";
        return String.format(
                "%s · %s · %s · 中心 %d, %d · %d 个区块",
                quality,
                request.dimension().displayName(),
                request.layer(),
                result.center().x(),
                result.center().z(),
                result.sampledChunks());
    }

    private static String formatCachedPreviewStatus(PreviewRequest request) {
        return String.format(
                "已加载缓存 · %s · %s · 中心 %d, %d",
                request.dimension().displayName(),
                request.layer(),
                request.center().x(),
                request.center().z());
    }

    private static String shortMessage(Throwable failure) {
        if (failure == null) {
            return "请查看日志";
        }
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.length() <= 120 ? message : message.substring(0, 117) + "...";
    }

    static String formatGameTime(long ticks) {
        long totalMinutes = Math.max(0, ticks) / 20 / 60;
        long days = totalMinutes / (24 * 60);
        long hours = totalMinutes % (24 * 60) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        }
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        }
        return String.format("%dm", minutes);
    }

    private void clearDetails() {
        cancelPreviewTask();
        clearExportState();
        previewWorld = null;
        dimensionPreviewStates.clear();
        clearPreviewControls();
        worldNameLabel.setText(NO_SELECTION);
        versionLabel.setText(NOT_AVAILABLE);
        gameModeLabel.setText(NOT_AVAILABLE);
        folderCreationTimeLabel.setText(NOT_AVAILABLE);
        lastPlayedLabel.setText(NOT_AVAILABLE);
        gameTimeLabel.setText(NOT_AVAILABLE);
        seedLabel.setText(NOT_AVAILABLE);
        spawnPosLabel.setText(NOT_AVAILABLE);
        playerPosLabel.setText(NOT_AVAILABLE);
        previewImageView.setImage(null);
        setPreviewIdle(PREVIEW_NO_SELECTION);
    }

    @FXML
    public void handleExportPreview(ActionEvent event) {
        PreviewExportSource source = previewExportSource;
        if (source == null) {
            previewStatusLabel.setText(EXPORT_NO_PREVIEW);
            exportPreviewButton.setDisable(true);
            return;
        }

        try {
            Path exported = previewExporter.exportToDefault(
                    source.imagePath(),
                    source.worldName(),
                    source.worldDirectory());
            showExportSuccess(source, exported);
        } catch (ExportException e) {
            LOGGER.error("Failed to export preview {}", source.imagePath(), e);
            if (e.reason() == FailureReason.TARGET_DIRECTORY_UNAVAILABLE
                    || e.reason() == FailureReason.TARGET_INSIDE_WORLD) {
                offerAlternateExportLocation(source, e);
            } else {
                showExportFailure(source, e);
            }
        }
    }

    private void offerAlternateExportLocation(PreviewExportSource source, ExportException failure) {
        ButtonType chooseLocation = new ButtonType("选择其他位置", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(worldTreeView.getScene().getWindow());
        alert.setTitle("默认导出目录不可用");
        alert.setHeaderText("无法导出到程序根目录的 exports 文件夹");
        alert.setContentText(failure.getMessage());
        alert.getButtonTypes().setAll(chooseLocation, ButtonType.CANCEL);
        if (alert.showAndWait().filter(chooseLocation::equals).isPresent()) {
            chooseAlternateExportLocation(source);
        }
    }

    private void chooseAlternateExportLocation(PreviewExportSource source) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 PNG 导出位置");
        chooser.setInitialFileName(previewExporter.suggestedFileName(source.worldName()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));

        while (isCurrentExportSource(source)) {
            File selectedFile = chooser.showSaveDialog(worldTreeView.getScene().getWindow());
            if (selectedFile == null) {
                return;
            }
            try {
                Path exported = previewExporter.exportToFile(
                        source.imagePath(),
                        selectedFile.toPath(),
                        source.worldDirectory());
                showExportSuccess(source, exported);
                return;
            } catch (ExportException e) {
                LOGGER.error("Failed to export preview {} to {}", source.imagePath(), selectedFile, e);
                if (e.reason() == FailureReason.TARGET_EXISTS
                        || e.reason() == FailureReason.TARGET_INSIDE_WORLD) {
                    showAlternateTargetWarning(e);
                    continue;
                }
                showExportFailure(source, e);
                return;
            }
        }
    }

    private void showAlternateTargetWarning(ExportException failure) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(worldTreeView.getScene().getWindow());
        alert.setTitle("无法使用所选位置");
        alert.setHeaderText(failure.reason() == FailureReason.TARGET_EXISTS
                ? "不会覆盖已有文件"
                : "不会向 Minecraft 存档写入文件");
        alert.setContentText(failure.getMessage() + "\n请重新选择或取消。");
        alert.showAndWait();
    }

    private void setExportSource(Path imagePath, WorldInfo world) {
        previewExportSource = new PreviewExportSource(
                imagePath.toAbsolutePath().normalize(),
                world.getFolderPath().toAbsolutePath().normalize(),
                world.getLevelName());
        exportPreviewButton.setDisable(false);
    }

    private void clearExportState() {
        previewExportSource = null;
        if (exportPreviewButton != null) {
            exportPreviewButton.setDisable(true);
        }
    }

    private boolean isCurrentExportSource(PreviewExportSource source) {
        return previewExportSource == source;
    }

    private void showExportSuccess(PreviewExportSource source, Path exported) {
        if (isCurrentExportSource(source)) {
            previewStatusLabel.setText("已导出 PNG：" + exported.toAbsolutePath().normalize());
        }
    }

    private void showExportFailure(PreviewExportSource source, ExportException failure) {
        if (isCurrentExportSource(source)) {
            previewStatusLabel.setText("导出失败：" + failure.getMessage());
            if (failure.reason() == FailureReason.SOURCE_UNAVAILABLE) {
                clearExportState();
            }
        }
    }

    private final class PreviewTask extends Task<PreviewDisplay> {
        private final WorldInfo world;
        private final WorldDimension dimension;
        private final PreviewLayer requestedLayer;
        private final Integer preferredSliderY;

        private PreviewTask(
                WorldInfo world,
                WorldDimension dimension,
                PreviewLayer requestedLayer,
                Integer preferredSliderY) {
            this.world = world;
            this.dimension = dimension;
            this.requestedLayer = requestedLayer;
            this.preferredSliderY = preferredSliderY;
        }

        @Override
        protected PreviewDisplay call() throws IOException {
            DimensionHeightRange heightRange = DimensionHeightResolver.resolve(dimension);
            PreviewRequest request = PreviewRequestResolver.resolve(
                    world,
                    dimension,
                    heightRange,
                    requestedLayer);
            Optional<PreviewCacheResult> reusable = previewCache.findReusable(world, request);
            if (reusable.isPresent()) {
                return new PreviewDisplay(
                        reusable.orElseThrow(),
                        null,
                        request,
                        heightRange);
            }
            PreviewGenerationResult generation = previewGenerator.generate(
                    world,
                    request,
                    new PreviewGenerationMonitor() {
                        @Override
                        public boolean isCancelled() {
                            return PreviewTask.this.isCancelled();
                        }

                        @Override
                        public void onProgress(int completedChunks, int totalChunks) {
                            updateProgress(completedChunks, totalChunks);
                        }
                    });
            if (isCancelled()) {
                throw new CancellationException("preview generation cancelled");
            }
            return new PreviewDisplay(
                    previewCache.store(world, request, generation),
                    generation,
                    request,
                    heightRange);
        }
    }

    private record PreviewDisplay(
            PreviewCacheResult cache,
            PreviewGenerationResult generation,
            PreviewRequest request,
            DimensionHeightRange heightRange) {
    }

    static record DimensionPreviewState(
            DimensionHeightRange heightRange,
            int sliderY,
            PreviewLayer selectedLayer) {
        DimensionPreviewState withSliderY(int nextSliderY) {
            return new DimensionPreviewState(heightRange, nextSliderY, selectedLayer);
        }
    }

    static final class DimensionPreviewStateStore {
        private final Map<String, DimensionPreviewState> states = new HashMap<>();

        DimensionPreviewState get(WorldDimension dimension) {
            return states.get(dimension.id());
        }

        void put(WorldDimension dimension, DimensionPreviewState state) {
            states.put(dimension.id(), state);
        }

        void clear() {
            states.clear();
        }
    }

    private record PreviewExportSource(
            Path imagePath,
            Path worldDirectory,
            String worldName) {
    }
}
