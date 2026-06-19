package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

public class WorldListCell extends ListCell<WorldInfo> {
    private HBox content;
    private ImageView iconView;
    private Text nameText;

    public WorldListCell() {
        super();
        iconView = new ImageView();
        iconView.setFitWidth(32);
        iconView.setFitHeight(32);
        iconView.setPreserveRatio(true);

        nameText = new Text();
        
        content = new HBox(10);
        content.getChildren().addAll(iconView, nameText);
        content.setStyle("-fx-alignment: center-left; -fx-padding: 5;");
    }

    @Override
    protected void updateItem(WorldInfo item, boolean empty) {
        super.updateItem(item, empty);
        
        if (empty || item == null) {
            setGraphic(null);
            setText(null);
        } else {
            nameText.setText(item.getLevelName() != null ? item.getLevelName() : item.getFolderPath().getFileName().toString());
            
            if (item.getIconPath() != null) {
                // JavaFX requires file: prefix for local absolute paths
                try {
                    Image image = new Image("file:" + item.getIconPath().toAbsolutePath().toString(), 32, 32, true, true);
                    iconView.setImage(image);
                } catch (Exception e) {
                    iconView.setImage(null);
                }
            } else {
                iconView.setImage(null);
            }
            
            setGraphic(content);
        }
    }
}
