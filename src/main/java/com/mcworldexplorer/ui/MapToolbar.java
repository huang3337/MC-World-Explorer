package com.mcworldexplorer.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.FlowPane;

/** Keep the resources entry in Tools when the full row would wrap. */
public final class MapToolbar extends FlowPane {
    private Button resources;
    private MenuButton tools;
    private ComboBox<?> dimension;
    private double expandedDimensionWidth;

    void configure(Button resources, MenuButton tools, MenuItem item, ComboBox<?> dimension) {
        this.resources = resources;
        this.tools = tools;
        this.dimension = dimension;
        expandedDimensionWidth = dimension.getPrefWidth();
        resources.managedProperty().bind(resources.visibleProperty());
        item.visibleProperty().bind(resources.visibleProperty().not());
        item.disableProperty().bind(resources.disableProperty());
    }

    @Override
    protected void layoutChildren() {
        if (resources != null) {
            double fullWidth = snappedLeftInset() + snappedRightInset();
            for (Node control : getChildren()) {
                // Include the hidden button so widening the window restores it.
                fullWidth += snapSizeX(control.prefWidth(-1));
            }
            fullWidth += (getChildren().size() - 1) * snapSpaceX(getHgap());
            fullWidth += snapSizeX(expandedDimensionWidth) - snapSizeX(dimension.getPrefWidth());
            boolean fits = fullWidth <= getWidth();
            if (!fits && resources.isFocused()) tools.requestFocus();
            resources.setVisible(fits);
            double compactWidth = fullWidth - snapSizeX(resources.prefWidth(-1)) - snapSpaceX(getHgap());
            double shortage = Math.max(0, compactWidth - getWidth());
            dimension.setPrefWidth(Math.max(dimension.getMinWidth(), expandedDimensionWidth - Math.ceil(shortage)));
        }
        super.layoutChildren();
    }
}
