package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;
import java.util.Map;
import java.util.LinkedHashMap;
import javafx.event.ActionEvent;
import javafx.stage.DirectoryChooser;

public class MainController {
    private static final String UNKNOWN = "Unknown";
    private static final String NOT_AVAILABLE = "-";
    private static final String NO_SELECTION = "Select a World";
    private static final String PARSE_FAILED = "解析失败";
    private static final String CHOOSE_FOLDER_TITLE = "Select Minecraft Saves Folder";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @FXML
    private TreeView<WorldTreeNode> worldTreeView;

    @FXML
    private Label worldNameLabel;

    @FXML
    private Label versionLabel;

    @FXML
    private Label gameModeLabel;

    @FXML
    private Label lastPlayedLabel;

    @FXML
    private Label seedLabel;

    @FXML
    private Label playerPosLabel;

    @FXML
    public void initialize() {
        clearDetails();

        // Setup custom cell factory for icons and names
        worldTreeView.setCellFactory(treeView -> new WorldTreeCell());

        // Listen for selection changes
        worldTreeView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && newValue.getValue().getWorldInfo() != null) {
                        showWorldDetails(newValue.getValue().getWorldInfo());
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

        Map<String, List<WorldInfo>> groupedWorlds = new LinkedHashMap<>();
        if (rootPath != null && Files.exists(rootPath)) {
            groupedWorlds = WorldScanner.scanGameRoot(rootPath);
        }

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
            lastPlayedLabel.setText(PARSE_FAILED);
            seedLabel.setText(PARSE_FAILED);
            playerPosLabel.setText(PARSE_FAILED);
            return;
        }

        String modeStr = info.getGameType().getDisplayName();
        if (info.isHardcore()) {
            modeStr += " (Hardcore)";
        }
        gameModeLabel.setText(modeStr);

        // Format Date
        if (info.getLastPlayed() > 0) {
            lastPlayedLabel.setText(DATE_FORMAT.format(new Date(info.getLastPlayed())));
        } else {
            lastPlayedLabel.setText(UNKNOWN);
        }

        seedLabel.setText(info.isSeedAvailable() ? String.valueOf(info.getRandomSeed()) : UNKNOWN);

        if (info.isPlayerPositionAvailable()) {
            String pos = String.format("%.1f, %.1f, %.1f", info.getPlayerX(), info.getPlayerY(), info.getPlayerZ());
            playerPosLabel.setText(pos);
        } else {
            playerPosLabel.setText(UNKNOWN);
        }
    }

    private void clearDetails() {
        worldNameLabel.setText(NO_SELECTION);
        versionLabel.setText(NOT_AVAILABLE);
        gameModeLabel.setText(NOT_AVAILABLE);
        lastPlayedLabel.setText(NOT_AVAILABLE);
        seedLabel.setText(NOT_AVAILABLE);
        playerPosLabel.setText(NOT_AVAILABLE);
    }
}
