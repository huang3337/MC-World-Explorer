package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.DimensionHeightRange;
import com.mcworldexplorer.preview.PreviewGenerationResult;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.PreviewRequest;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final PortableSettings portableSettings = new PortableSettings();
    private Path selectedRootPath;
    private String pendingScanWarning;

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
    private MapViewerController mapViewerController;

    @FXML
    public void initialize() {
        clearDetails();
        worldTreeView.setCellFactory(treeView -> new WorldTreeCell());
        worldTreeView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && newValue.getValue().getWorldInfo() != null) {
                        WorldInfo world = newValue.getValue().getWorldInfo();
                        showWorldDetails(world);
                        mapViewerController.setWorld(world);
                    } else {
                        clearDetails();
                    }
                });
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
            setScanState(false, withScanWarning(
                    worldCount == 0 ? NO_WORLDS : worldCount + " worlds"));
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
            TreeItem<WorldTreeNode> groupItem = new TreeItem<>(
                    WorldTreeNode.group(entry.getKey()));
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
            File currentDirectory = selectedRootPath.toFile();
            if (currentDirectory.exists() && currentDirectory.isDirectory()) {
                chooser.setInitialDirectory(currentDirectory);
            }
        }
        File selectedDirectory = chooser.showDialog(worldTreeView.getScene().getWindow());
        if (selectedDirectory == null) {
            return;
        }
        selectedRootPath = selectedDirectory.toPath().toAbsolutePath().normalize();
        try {
            portableSettings.saveCustomSavesPath(selectedRootPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save portable settings", e);
            pendingScanWarning = "目录可用，但无法保存到本地配置";
        }
        startWorldScan(selectedRootPath);
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
        String mode = info.getGameType().getDisplayName();
        gameModeLabel.setText(info.isHardcore() ? mode + " (Hardcore)" : mode);
        folderCreationTimeLabel.setText(info.isFolderCreationTimeAvailable()
                ? DATE_FORMAT.format(new Date(info.getFolderCreationTime()))
                : UNKNOWN);
        lastPlayedLabel.setText(info.getLastPlayed() > 0
                ? DATE_FORMAT.format(new Date(info.getLastPlayed()))
                : UNKNOWN);
        gameTimeLabel.setText(formatGameTime(info.getGameTime()));
        seedLabel.setText(info.isSeedAvailable()
                ? String.valueOf(info.getRandomSeed())
                : UNKNOWN);
        spawnPosLabel.setText(info.isSpawnPositionAvailable()
                ? String.format("%d, %d, %d", info.getSpawnX(), info.getSpawnY(), info.getSpawnZ())
                : UNKNOWN);
        playerPosLabel.setText(info.isPlayerPositionAvailable()
                ? String.format("%.1f, %.1f, %.1f",
                        info.getPlayerX(), info.getPlayerY(), info.getPlayerZ())
                : UNKNOWN);
    }

    private void clearDetails() {
        if (mapViewerController != null) {
            mapViewerController.clear();
        }
        worldNameLabel.setText(NO_SELECTION);
        versionLabel.setText(NOT_AVAILABLE);
        gameModeLabel.setText(NOT_AVAILABLE);
        folderCreationTimeLabel.setText(NOT_AVAILABLE);
        lastPlayedLabel.setText(NOT_AVAILABLE);
        gameTimeLabel.setText(NOT_AVAILABLE);
        seedLabel.setText(NOT_AVAILABLE);
        spawnPosLabel.setText(NOT_AVAILABLE);
        playerPosLabel.setText(NOT_AVAILABLE);
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

    static boolean shouldShowPreviewPlaceholder(boolean imagePresent) {
        return !imagePresent;
    }

    static boolean shouldSkipLayerRequest(
            PreviewLayer requestedLayer,
            PreviewLayer selectedLayer,
            boolean imagePresent) {
        return imagePresent && requestedLayer.equals(selectedLayer);
    }

    static PreviewLayer layerForSlider(
            DimensionHeightRange heightRange,
            double sliderValue) {
        return heightRange.bandContaining(sliderCoordinate(heightRange, sliderValue));
    }

    static int sliderCoordinate(
            DimensionHeightRange heightRange,
            double sliderValue) {
        if (heightRange == null) {
            throw new IllegalArgumentException("heightRange must not be null");
        }
        if (!Double.isFinite(sliderValue)) {
            return heightRange.minY();
        }
        long floored = (long) Math.floor(sliderValue);
        return (int) Math.max(
                heightRange.minY(),
                Math.min(heightRange.maxY(), floored));
    }

    static String formatLayerSliderLabel(
            DimensionHeightRange heightRange,
            double sliderValue) {
        int y = sliderCoordinate(heightRange, sliderValue);
        PreviewLayer layer = heightRange.bandContaining(y);
        return String.format("Y %d · 区间 Y %d - %d", y, layer.minY(), layer.maxY());
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
}
