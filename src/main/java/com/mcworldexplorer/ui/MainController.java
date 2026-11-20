package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.DimensionHeightRange;
import com.mcworldexplorer.preview.PreviewGenerationResult;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.PreviewRequest;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.text.Collator;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class MainController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainController.class);
    private static final String UNKNOWN = "未知";
    private static final String NOT_AVAILABLE = "-";
    private static final String NO_SELECTION = "选择一个世界";
    private static final String PARSE_FAILED = "解析失败";
    private static final String CHOOSE_FOLDER_TITLE = "选择 Minecraft 目录";
    private static final String SCANNING = "正在查找世界…";
    private static final String NO_WORLDS = "未找到可识别的世界，请选择其他目录";
    private static final String FOLDER_NOT_FOUND = "目录不存在，请选择 Minecraft 目录";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final PortableSettings portableSettings = new PortableSettings();
    private Path selectedRootPath;
    private String pendingScanWarning;
    private String libraryNotice = "";
    private Map<String, List<WorldInfo>> library = Map.of();
    private Path openWorldPath;
    private boolean updatingTree;
    private Task<WorldScanner.ScanResult> activeScan;
    private double sidebarPosition = 0.23;
    private Consumer<String> documentOpener = address -> { throw new IllegalStateException("浏览器服务尚未初始化"); };
    private Dialog<Void> aboutDialog;

    public void setDocumentOpener(Consumer<String> opener) {
        documentOpener = Objects.requireNonNull(opener);
    }

    @FXML
    private void handleSettings() {
        var owner = (javafx.stage.Stage) workspaceSplitPane.getScene().getWindow();
        try {
            SettingsDialog.show(owner, portableSettings, mapViewerController, selectedRootPath, activeScan != null,
                    this::useLibraryRoot);
        } catch (IOException failure) {
            var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                    "无法读取设置：" + failure.getMessage());
            alert.initOwner(owner);
            alert.showAndWait();
        }
    }

    @FXML
    private void handleAbout() {
        if (aboutDialog != null) {
            aboutDialog.getDialogPane().getScene().getWindow().requestFocus();
            return;
        }
        aboutDialog = AboutDialog.create(workspaceSplitPane.getScene().getWindow(), documentOpener);
        aboutDialog.setOnHidden(event -> aboutDialog = null);
        aboutDialog.show();
    }

    @FXML
    private SplitPane workspaceSplitPane;
    @FXML
    private VBox worldSidebar;
    @FXML
    private ToggleButton detailsToggleButton;
    @FXML
    private ScrollPane detailsPane;
    private final FadeTransition detailsFade = new FadeTransition();
    @FXML
    private Label folderPathLabel;
    @FXML
    private Button copyPathButton;
    @FXML
    private Label worldSummaryLabel;

    @FXML
    private TreeView<WorldTreeNode> worldTreeView;
    @FXML
    private Button chooseFolderButton;
    @FXML
    private Button refreshLibraryButton;
    @FXML
    private TextField worldSearch;
    @FXML
    private RadioMenuItem sortByName;
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

    public void shutdown() {
        detailsFade.stop();
        if (activeScan != null) {
            activeScan.cancel();
            activeScan = null;
        }
        if (aboutDialog != null) aboutDialog.close();
        if (mapViewerController != null) {
            mapViewerController.shutdown();
        }
    }

    @FXML
    public void initialize() {
        mapViewerController.attachDetailsPane(detailsPane);
        detailsPane.setOpacity(0);
        detailsPane.managedProperty().bind(detailsPane.visibleProperty());
        detailsPane.translateXProperty().bind(detailsPane.opacityProperty().negate().add(1).multiply(10));
        detailsFade.setNode(detailsPane);
        detailsFade.setInterpolator(Interpolator.EASE_BOTH);
        detailsFade.setOnFinished(event -> detailsPane.setVisible(detailsToggleButton.isSelected()));
        detailsToggleButton.selectedProperty().addListener((obs, before, open) -> transitionDetails(open));
        workspaceSplitPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && detailsToggleButton.isSelected()) {
                handleCloseDetails(null);
                event.consume();
            }
        });
        clearDetails();
        worldTreeView.setCellFactory(treeView -> new WorldTreeCell());
        worldTreeView.getSelectionModel().selectedItemProperty().addListener((observable, oldItem, item) -> {
            if (updatingTree || item == null || item.getValue().isGroup()) return;
            WorldInfo world = item.getValue().getWorldInfo();
            Path path = worldPath(world);
            if (!path.equals(openWorldPath)) {
                openWorldPath = path;
                showWorldDetails(world);
                mapViewerController.setWorld(world);
            }
        });
        worldSearch.textProperty().addListener((observable, oldText, text) -> updateWorldTree());
        sortByName.selectedProperty().addListener((observable, previous, selected) -> updateWorldTree());
        loadWorlds();
    }

    private void loadWorlds() {
        try {
            selectedRootPath = portableSettings.loadCustomSavesPath()
                    .orElseGet(WorldScanner::getDefaultGameRoot);
        } catch (IOException e) {
            LOGGER.warn("Failed to read portable settings", e);
            pendingScanWarning = "本地配置读取失败，已使用默认目录";
            selectedRootPath = WorldScanner.getDefaultGameRoot();
        }
        if (selectedRootPath == null || Files.notExists(selectedRootPath)) {
            libraryNotice = withScanWarning(FOLDER_NOT_FOUND);
            updateWorldTree();
            return;
        }
        startWorldScan();
    }

    @FXML
    private void handleRefreshLibrary() {
        startWorldScan();
    }

    private void startWorldScan() {
        if (activeScan != null || selectedRootPath == null) return;
        Path root = selectedRootPath;
        Map<String, List<WorldInfo>> previous = library;
        Task<WorldScanner.ScanResult> scan = new Task<>() {
            @Override
            protected WorldScanner.ScanResult call() {
                return WorldScanner.scanLibrary(root, previous);
            }
        };
        activeScan = scan;
        setScanBusy(true);
        scanStatusLabel.setText(SCANNING);
        scan.setOnSucceeded(event -> {
            if (activeScan != scan) return;
            activeScan = null;
            setScanBusy(false);
            applyScan(scan.getValue());
        });
        scan.setOnFailed(event -> {
            if (activeScan != scan) return;
            activeScan = null;
            setScanBusy(false);
            LOGGER.warn("Failed to scan {}", root, scan.getException());
            libraryNotice = withScanWarning(library.isEmpty()
                    ? "目录读取失败，可点击刷新重试" : "刷新未完成，保留上次列表");
            updateWorldTree();
        });
        Thread scanThread = new Thread(scan, "world-scanner");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void applyScan(WorldScanner.ScanResult scan) {
        library = scan.withLastReadable(library);
        String notice = "";
        if (!scan.failedPaths().isEmpty()) {
            Path first = scan.failedPaths().iterator().next();
            String location = first.getFileName() == null ? first.toString() : first.getFileName().toString();
            notice = "读取未完成 · " + location;
            if (scan.failedPaths().size() > 1) notice += " 等 " + scan.failedPaths().size() + " 处";
            int retained = countWorlds(library) - countWorlds(scan.groups());
            if (retained > 0) notice += " · 保留 " + retained + " 个世界的上次信息";
        }
        if (openWorldPath != null) {
            WorldInfo current = library.values().stream().flatMap(List::stream)
                    .filter(world -> openWorldPath.equals(worldPath(world))).findFirst().orElse(null);
            if (current == null) {
                openWorldPath = null;
                clearDetails();
                notice = notice.isEmpty() ? "当前世界已不在此目录" : notice + " · 当前世界已不在此目录";
            } else {
                // The map owns the open browsing session. Only the library details use fresh metadata.
                showWorldDetails(current);
            }
        }
        libraryNotice = withScanWarning(notice);
        updateWorldTree();
    }

    private static Path worldPath(WorldInfo world) {
        return world.getFolderPath().toAbsolutePath().normalize();
    }

    private static String folderName(WorldInfo world) {
        Path name = world.getFolderPath().getFileName();
        return name == null ? world.getFolderPath().toString() : name.toString();
    }

    private void updateWorldTree() {
        String query = worldSearch.getText().strip().toLowerCase(Locale.ROOT);
        Collator chinese = Collator.getInstance(Locale.CHINA);
        Comparator<WorldInfo> byName = Comparator.comparing(WorldInfo::getLevelName, chinese)
                .thenComparing(MainController::folderName, chinese)
                .thenComparing(MainController::worldPath);
        Comparator<WorldInfo> order = sortByName.isSelected() ? byName
                : Comparator.comparingLong(WorldInfo::getLastPlayed).reversed().thenComparing(byName);
        TreeItem<WorldTreeNode> root = new TreeItem<>(WorldTreeNode.group("Root"));
        root.setExpanded(true);
        TreeItem<WorldTreeNode> selected = null;
        int visible = 0;
        for (var entry : library.entrySet()) {
            TreeItem<WorldTreeNode> group = new TreeItem<>(WorldTreeNode.group(entry.getKey()));
            group.setExpanded(true);
            List<WorldInfo> matches = entry.getValue().stream().filter(world ->
                    world.getLevelName().toLowerCase(Locale.ROOT).contains(query)
                            || folderName(world).toLowerCase(Locale.ROOT).contains(query))
                    .sorted(order).toList();
            for (WorldInfo world : matches) {
                TreeItem<WorldTreeNode> item = new TreeItem<>(WorldTreeNode.world(world));
                group.getChildren().add(item);
                if (worldPath(world).equals(openWorldPath)) selected = item;
            }
            if (!matches.isEmpty()) root.getChildren().add(group);
            visible += matches.size();
        }
        updatingTree = true;
        try {
            worldTreeView.getSelectionModel().clearSelection();
            worldTreeView.setRoot(root);
            worldTreeView.setShowRoot(false);
            if (selected != null) worldTreeView.getSelectionModel().select(selected);
        } finally {
            updatingTree = false;
        }
        if (activeScan != null) return;
        int total = countWorlds(library);
        String status;
        if (total == 0) {
            status = libraryNotice.isEmpty() ? NO_WORLDS : "";
        } else if (query.isEmpty()) {
            status = "已找到 " + total + " 个世界";
        } else {
            status = "显示 " + visible + " / " + total + " 个世界";
        }
        scanStatusLabel.setText(joinStatus(status, libraryNotice));
    }

    private void setScanBusy(boolean busy) {
        chooseFolderButton.setDisable(busy);
        refreshLibraryButton.setDisable(busy);
        scanProgressIndicator.setManaged(busy);
        scanProgressIndicator.setVisible(busy);
    }

    private String withScanWarning(String status) {
        if (pendingScanWarning == null) return status;
        String combined = joinStatus(status, pendingScanWarning);
        pendingScanWarning = null;
        return combined;
    }

    private static String joinStatus(String status, String notice) {
        if (status.isEmpty()) return notice;
        return notice.isEmpty() ? status : status + " · " + notice;
    }

    static int countWorlds(Map<String, List<WorldInfo>> groupedWorlds) {
        return groupedWorlds.values().stream().mapToInt(List::size).sum();
    }

    @FXML
    private void handleToggleSidebar(ActionEvent event) {
        if (workspaceSplitPane.getItems().contains(worldSidebar)) {
            sidebarPosition = workspaceSplitPane.getDividerPositions()[0];
            workspaceSplitPane.getItems().remove(worldSidebar);
        } else {
            workspaceSplitPane.getItems().add(0, worldSidebar);
            workspaceSplitPane.setDividerPositions(sidebarPosition);
        }
    }

    @FXML
    private void handleCloseDetails(ActionEvent event) {
        detailsToggleButton.setSelected(false);
        detailsToggleButton.requestFocus();
    }

    private void transitionDetails(boolean open) {
        double from = detailsPane.getOpacity();
        double target = open ? 1 : 0;
        detailsFade.stop();
        detailsPane.setOpacity(from);
        detailsPane.setMouseTransparent(!open);
        if (open) copyPathButton.setText("复制");
        var scene = detailsPane.getScene();
        if (from == target || scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            detailsPane.setOpacity(target);
            detailsPane.setVisible(open);
            return;
        }
        detailsPane.setVisible(true);
        detailsFade.setFromValue(from);
        detailsFade.setToValue(target);
        detailsFade.setDuration(Duration.millis(Math.abs(target - from) * (open ? 150 : 100)));
        detailsFade.playFromStart();
    }

    @FXML
    private void handleCopyPath() {
        var content = new ClipboardContent();
        content.putString(folderPathLabel.getText());
        try {
            boolean copied = Clipboard.getSystemClipboard().setContent(content);
            copyPathButton.setText(copied ? "已复制" : "重试");
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not copy world folder path", failure);
            copyPathButton.setText("重试");
        }
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
        Path nextRoot = selectedDirectory.toPath().toAbsolutePath().normalize();
        try {
            portableSettings.saveCustomSavesPath(nextRoot);
        } catch (IOException e) {
            LOGGER.error("Failed to save portable settings", e);
            pendingScanWarning = "目录可用，但无法保存到本地配置";
        }
        useLibraryRoot(nextRoot);
    }

    private void useLibraryRoot(Path nextRoot) {
        if (!nextRoot.equals(selectedRootPath)) {
            openWorldPath = null;
            library = Map.of();
            libraryNotice = "";
            clearDetails();
            worldSearch.clear();
            updateWorldTree();
        }
        selectedRootPath = nextRoot;
        startWorldScan();
    }

    private void showWorldDetails(WorldInfo info) {
        if (info == null) {
            clearDetails();
            return;
        }
        worldNameLabel.setText(info.getLevelName());
        worldNameLabel.setTooltip(new Tooltip(info.getLevelName()));
        String version = "Unknown".equals(info.getVersionName()) ? UNKNOWN : info.getVersionName();
        versionLabel.setText(version);
        String folder = folderName(info);
        String summary = version + " · " + (info.isParsed() ? "只读浏览" : PARSE_FAILED);
        worldSummaryLabel.setText(folder.equals(info.getLevelName()) ? summary : summary + " · " + folder);
        String path = worldPath(info).toString();
        worldSummaryLabel.setTooltip(new Tooltip(path));
        if (!path.equals(folderPathLabel.getText())) detailsPane.setVvalue(0);
        folderPathLabel.setText(path);
        folderPathLabel.setTooltip(new Tooltip(path));
        copyPathButton.setText("复制");
        copyPathButton.setDisable(false);
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
        String mode = switch (info.getGameType()) {
            case SURVIVAL -> "生存";
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
        };
        gameModeLabel.setText(info.isHardcore() ? mode + "（极限）" : mode);
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
        worldNameLabel.setTooltip(null);
        worldSummaryLabel.setText("无需启动 Minecraft，即可浏览已有世界");
        worldSummaryLabel.setTooltip(null);
        folderPathLabel.setText(NOT_AVAILABLE);
        folderPathLabel.setTooltip(null);
        copyPathButton.setText("复制");
        copyPathButton.setDisable(true);
        detailsPane.setVvalue(0);
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
            return String.format("%d 天 %d 小时 %d 分钟", days, hours, minutes);
        }
        if (hours > 0) {
            return String.format("%d 小时 %d 分钟", hours, minutes);
        }
        return String.format("%d 分钟", minutes);
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
