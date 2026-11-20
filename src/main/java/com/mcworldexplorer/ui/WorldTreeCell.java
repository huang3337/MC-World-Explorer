package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;
import javafx.scene.control.TreeCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;

public class WorldTreeCell extends TreeCell<WorldTreeNode> {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldTreeCell.class);
    private static final String GROUP_STYLE_CLASS = "tree-cell-group";
    private static final String WORLD_STYLE_CLASS = "tree-cell-world";
    private static final Map<WorldInfo, Image> ICON_CACHE = new WeakHashMap<>();
    private final ImageView iconView = new ImageView();

    public WorldTreeCell() {
        iconView.setFitWidth(32);
        iconView.setFitHeight(32);
        iconView.setPreserveRatio(true);
    }

    @Override
    protected void updateItem(WorldTreeNode item, boolean empty) {
        super.updateItem(item, empty);
        getStyleClass().removeAll(GROUP_STYLE_CLASS, WORLD_STYLE_CLASS);
        if (empty || item == null) {
            setText(null);
            setGraphic(null);
        } else if (item.isGroup()) {
            setText(item.getGroupName());
            getStyleClass().add(GROUP_STYLE_CLASS);
            setGraphic(null);
        } else {
            WorldInfo info = item.getWorldInfo();
            setText(info.getLevelName());
            getStyleClass().add(WORLD_STYLE_CLASS);
            try {
                Path iconPath = info.getIconPath();
                if (iconPath != null) {
                    Image icon = ICON_CACHE.computeIfAbsent(info,
                            world -> new Image(iconPath.toUri().toString(), 32, 32, true, true));
                    iconView.setImage(icon);
                    setGraphic(iconView);
                } else {
                    setGraphic(null);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to load icon for {}", info.getLevelName(), e);
                setGraphic(null);
            }
        }
    }
}
