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

    @FXML
    private TreeView<Object> worldTreeView;

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
                    if (newValue != null && newValue.getValue() instanceof WorldInfo) {
                        showWorldDetails((WorldInfo) newValue.getValue());
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

        TreeItem<Object> rootItem = new TreeItem<>("Root");
        rootItem.setExpanded(true);
        for (Map.Entry<String, List<WorldInfo>> entry : groupedWorlds.entrySet()) {
            TreeItem<Object> groupItem = new TreeItem<>(entry.getKey());
            groupItem.setExpanded(true);
            for (WorldInfo info : entry.getValue()) {
                groupItem.getChildren().add(new TreeItem<>(info));
            }
            rootItem.getChildren().add(groupItem);
        }

        worldTreeView.setRoot(rootItem);
        worldTreeView.setShowRoot(false);
    }

    @FXML
    public void handleChooseFolder(ActionEvent event) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Minecraft saves Folder");
        
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

        worldNameLabel.setText(info.getLevelName() != null ? info.getLevelName() : info.getFolderPath().getFileName().toString());
        versionLabel.setText(info.getVersionName() != null ? info.getVersionName() : "Unknown");
        
        if ("解析失败".equals(info.getVersionName())) {
            gameModeLabel.setText("解析失败");
            lastPlayedLabel.setText("解析失败");
            seedLabel.setText("解析失败");
            playerPosLabel.setText("解析失败");
            return;
        }

        // Map GameType
        String modeStr = "Unknown";
        switch (info.getGameType()) {
            case 0: modeStr = "Survival"; break;
            case 1: modeStr = "Creative"; break;
            case 2: modeStr = "Adventure"; break;
            case 3: modeStr = "Spectator"; break;
        }
        if (info.isHardcore()) {
            modeStr += " (Hardcore)";
        }
        gameModeLabel.setText(modeStr);

        // Format Date
        if (info.getLastPlayed() > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            lastPlayedLabel.setText(sdf.format(new Date(info.getLastPlayed())));
        } else {
            lastPlayedLabel.setText("Unknown");
        }

        seedLabel.setText(info.getRandomSeed() != 0 ? String.valueOf(info.getRandomSeed()) : "Unknown");

        String pos = String.format("%.1f, %.1f, %.1f", info.getPlayerX(), info.getPlayerY(), info.getPlayerZ());
        playerPosLabel.setText(pos);
    }

    private void clearDetails() {
        worldNameLabel.setText("Select a World");
        versionLabel.setText("-");
        gameModeLabel.setText("-");
        lastPlayedLabel.setText("-");
        seedLabel.setText("-");
        playerPosLabel.setText("-");
    }
}
