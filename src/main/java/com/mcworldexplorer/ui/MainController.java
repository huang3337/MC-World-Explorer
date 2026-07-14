package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.PreviewCache;
import com.mcworldexplorer.preview.PreviewCacheResult;
import com.mcworldexplorer.preview.PreviewCenter;
import com.mcworldexplorer.preview.PreviewCenterResolver;
import com.mcworldexplorer.preview.PreviewGenerationMonitor;
import com.mcworldexplorer.preview.PreviewGenerationResult;
import com.mcworldexplorer.preview.PreviewGenerator;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.prefs.Preferences;

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
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final PreviewGenerator previewGenerator = new PreviewGenerator();
    private final PreviewCache previewCache = new PreviewCache();
    private Task<PreviewDisplay> previewTask;
    private long previewRequestId;

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
    public void initialize() {
        previewSurfacePane.widthProperty().addListener((observable, oldValue, newValue) -> updatePreviewImageSize());
        previewSurfacePane.heightProperty().addListener((observable, oldValue, newValue) -> updatePreviewImageSize());
        clearDetails();

        // Setup custom cell factory for icons and names
        worldTreeView.setCellFactory(treeView -> new WorldTreeCell());

        // Listen for selection changes
        worldTreeView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && newValue.getValue().getWorldInfo() != null) {
                        WorldInfo world = newValue.getValue().getWorldInfo();
                        showWorldDetails(world);
                        startPreview(world);
                    } else {
                        clearDetails();
                    }
                }
        );

        loadWorlds();
    }

    private void loadWorlds() {
        Preferences prefs = Preferences.userNodeForPackage(MainController.class);
        String savedPathStr = prefs.get("custom_saves_path", null);

        Path rootPath;
        if (savedPathStr != null) {
            rootPath = Paths.get(savedPathStr);
        } else {
            rootPath = WorldScanner.getDefaultGameRoot();
        }

        if (rootPath == null || !Files.isDirectory(rootPath)) {
            showWorlds(new LinkedHashMap<>());
            setScanState(false, FOLDER_NOT_FOUND);
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
            setScanState(false, worldCount == 0 ? NO_WORLDS : worldCount + " worlds");
        });
        scanTask.setOnFailed(event -> {
            LOGGER.error("Failed to scan selected Minecraft folder {}", rootPath, scanTask.getException());
            showWorlds(new LinkedHashMap<>());
            setScanState(false, SCAN_FAILED);
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

    static int countWorlds(Map<String, List<WorldInfo>> groupedWorlds) {
        return groupedWorlds.values().stream().mapToInt(List::size).sum();
    }

    @FXML
    public void handleChooseFolder(ActionEvent event) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(CHOOSE_FOLDER_TITLE);
        
        Preferences prefs = Preferences.userNodeForPackage(MainController.class);
        String savedPathStr = prefs.get("custom_saves_path", null);
        if (savedPathStr != null) {
            File currentDir = new File(savedPathStr);
            if (currentDir.exists() && currentDir.isDirectory()) {
                chooser.setInitialDirectory(currentDir);
            }
        }
        
        File selectedDir = chooser.showDialog(worldTreeView.getScene().getWindow());
        if (selectedDir != null) {
            prefs.put("custom_saves_path", selectedDir.getAbsolutePath());
            loadWorlds();
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

    private void startPreview(WorldInfo world) {
        cancelPreviewTask();
        previewImageView.setImage(null);
        previewPlaceholderLabel.setVisible(true);
        detailTabPane.getSelectionModel().select(previewTab);

        if (!world.isParsed()) {
            setPreviewIdle(PREVIEW_UNAVAILABLE);
            return;
        }

        PreviewCenter center = PreviewCenterResolver.resolve(world);
        try {
            Optional<PreviewCacheResult> reusable = previewCache.findReusable(world, center);
            if (reusable.isPresent() && showPreviewImage(reusable.orElseThrow().imagePath())) {
                setPreviewReady(String.format(
                        "已加载缓存 · 中心 %d, %d",
                        center.x(),
                        center.z()));
                return;
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to read preview cache for {}", world.getFolderPath(), e);
        }

        long requestId = previewRequestId;
        PreviewTask task = new PreviewTask(world);
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
            if (showPreviewImage(display.cache().imagePath())) {
                setPreviewReady(formatPreviewStatus(display.generation()));
            } else {
                showPreviewFailure("缓存图片无法读取");
            }
        });
        task.setOnFailed(event -> {
            if (!isCurrentPreview(task, requestId)) {
                return;
            }
            finishPreviewProgress();
            Throwable failure = task.getException();
            LOGGER.error("Failed to generate preview for {}", world.getFolderPath(), failure);
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
        previewImageView.setImage(null);
        previewPlaceholderLabel.setText("缩略图生成失败");
        previewPlaceholderLabel.setVisible(true);
        previewStatusLabel.setText(detail == null || detail.isBlank()
                ? "生成失败，请查看日志"
                : "生成失败：" + detail);
    }

    private void setPreviewIdle(String status) {
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

    private final class PreviewTask extends Task<PreviewDisplay> {
        private final WorldInfo world;

        private PreviewTask(WorldInfo world) {
            this.world = world;
        }

        @Override
        protected PreviewDisplay call() throws IOException {
            PreviewGenerationResult generation = previewGenerator.generate(
                    world,
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
            return new PreviewDisplay(previewCache.store(world, generation), generation);
        }
    }

    private record PreviewDisplay(
            PreviewCacheResult cache,
            PreviewGenerationResult generation) {
    }
}
