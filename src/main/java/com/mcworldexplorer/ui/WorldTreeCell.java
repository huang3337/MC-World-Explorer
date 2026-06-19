package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;
import javafx.scene.control.TreeCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorldTreeCell extends TreeCell<Object> {
    private final ImageView iconView = new ImageView();

    @Override
    protected void updateItem(Object item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setText(null);
            setGraphic(null);
        } else if (item instanceof String) {
            // It's a group (e.g. version name)
            setText((String) item);
            setStyle("-fx-font-weight: bold; -fx-padding: 5 0 5 0;");
            setGraphic(null);
        } else if (item instanceof WorldInfo) {
            // It's a world
            WorldInfo info = (WorldInfo) item;
            setText(info.getLevelName() != null ? info.getLevelName() : info.getFolderPath().getFileName().toString());
            setStyle("-fx-font-weight: normal; -fx-padding: 2 0 2 0;");
            try {
                Path iconPath = info.getFolderPath().resolve("icon.png");
                if (Files.exists(iconPath)) {
                    Image icon = new Image(iconPath.toUri().toString(), 32, 32, true, true);
                    iconView.setImage(icon);
                    setGraphic(iconView);
                } else {
                    setGraphic(null);
                }
            } catch (Exception e) {
                setGraphic(null);
            }
        }
    }
}
