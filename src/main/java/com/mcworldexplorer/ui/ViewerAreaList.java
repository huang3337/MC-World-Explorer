package com.mcworldexplorer.ui;

import com.mcworldexplorer.viewer.ViewerCachedArea;
import com.mcworldexplorer.viewer.ViewerPerformanceProfile;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.function.Consumer;

/** Compact overlay for the current world's and dimension's validated scene cache. */
public final class ViewerAreaList extends VBox {
    private final VBox cards = new VBox(8);
    private final Label footer = new Label();
    private final Button clear = new Button("清空");
    private Runnable onClear = () -> {};
    private Consumer<ViewerCachedArea> onOpen = ignored -> {};
    private Consumer<ViewerCachedArea> onRemove = ignored -> {};

    public ViewerAreaList(Runnable onClose) {
        super(10);
        setId("viewerAreaList");
        getStyleClass().add("viewer-area-panel");
        setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                onClose.run();
                event.consume();
            }
        });
        setMinWidth(0);
        setPrefWidth(256);
        setMaxWidth(256);
        Label title = new Label("已加载区域");
        title.getStyleClass().add("section-title");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        Button close = new Button("收起");
        close.setOnAction(event -> onClose.run());
        close.setId("viewerAreaListClose");
        clear.setId("viewerAreaListClear");
        clear.setTooltip(new Tooltip("清空当前列表及对应三维内存缓存，不修改存档。"));
        clear.setOnAction(event -> onClear.run());
        HBox header = new HBox(8, title, clear, close);
        header.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        scroll.getStyleClass().add("viewer-area-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        footer.setWrapText(true);
        footer.setMinWidth(0);
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setPrefWidth(228);
        footer.setMinHeight(40);
        footer.setPrefHeight(40);
        footer.setMaxHeight(40);
        footer.setId("viewerAreaListFooter");
        Tooltip footerTooltip = new Tooltip();
        footerTooltip.textProperty().bind(footer.textProperty());
        footer.setTooltip(footerTooltip);
        footer.getStyleClass().add("muted-text");
        getChildren().addAll(header, scroll, footer);
    }

    public void setOnOpen(Consumer<ViewerCachedArea> action) {
        onOpen = java.util.Objects.requireNonNull(action);
    }

    public void setOnRemove(Consumer<ViewerCachedArea> action) {
        onRemove = java.util.Objects.requireNonNull(action);
    }

    public void setOnClear(Runnable action) {
        onClear = java.util.Objects.requireNonNull(action);
    }

    public void setAreas(List<ViewerCachedArea> areas, ViewerPerformanceProfile profile, boolean running) {
        clear.setDisable(running || areas.isEmpty());
        cards.getChildren().clear();
        if (areas.isEmpty()) {
            Label empty = new Label("生成三维预览后，可在这里重新打开当前世界、当前维度的区域。");
            empty.setWrapText(true);
            empty.getStyleClass().add("muted-text");
            cards.getChildren().add(empty);
        }
        for (ViewerCachedArea area : areas) {
            var target = area.key().target();
            Label title = new Label(target.width() + " × " + target.depth() + " 区块");
            title.getStyleClass().add("section-title");
            Label coordinates = new Label("X " + target.minX() + ".." + target.maxX()
                    + "\nZ " + target.minZ() + ".." + target.maxZ());
            coordinates.setMinWidth(0);
            coordinates.setWrapText(true);
            coordinates.getStyleClass().add("muted-text");
            Tooltip.install(coordinates, new Tooltip(coordinates.getText()));
            VBox text = new VBox(3, title, coordinates);
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox overview = new HBox(9);
            overview.setAlignment(Pos.CENTER_LEFT);
            if (area.thumbnail().isPresent()) {
                var thumbnail = area.thumbnail().orElseThrow();
                WritableImage image = new WritableImage(thumbnail.width(), thumbnail.height());
                image.getPixelWriter().setPixels(0, 0, thumbnail.width(), thumbnail.height(),
                        PixelFormat.getIntArgbInstance(), thumbnail.argb(), 0, thumbnail.width());
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(80);
                imageView.setFitHeight(50);
                imageView.setPreserveRatio(true);
                imageView.setAccessibleText("二维地表地图缩略图，北朝上");
                Tooltip.install(imageView, new Tooltip("二维地图 · 地表总览 · 北朝上；暗色处暂无地图数据"));
                overview.getChildren().add(imageView);
            } else {
                Label unavailable = new Label("暂无缩略图");
                unavailable.getStyleClass().add("muted-text");
                overview.getChildren().add(unavailable);
            }
            overview.getChildren().add(text);
            Button open = new Button("重新打开");
            open.getStyleClass().add("viewer-area-open");
            open.setMaxWidth(Double.MAX_VALUE);
            boolean allowed = profile.allows(target);
            open.setDisable(running || !allowed);
            open.setOnAction(event -> onOpen.accept(area));
            VBox card = new VBox(8, overview);
            overview.setPadding(new javafx.geometry.Insets(0, 22, 0, 0));
            if (area.failedChunks() > 0) {
                Label partial = new Label(area.failedChunks() + " 个区块未完整加载");
                partial.getStyleClass().add("muted-text");
                partial.setWrapText(true);
                card.getChildren().add(partial);
            }
            if (!allowed) {
                Label hint = new Label("切换增强档后可重新打开");
                hint.getStyleClass().add("muted-text");
                card.getChildren().add(hint);
            }
            Button remove = new Button("×");
            remove.setAccessibleText("删除此已加载区域");
            remove.getStyleClass().add("viewer-area-remove");
            remove.setDisable(running);
            remove.setTooltip(new Tooltip("删除该条目及对应的三维内存缓存，不修改存档；已打开的窗口可继续查看。"));
            remove.setOnAction(event -> onRemove.accept(area));
            card.getChildren().add(open);
            StackPane wrapper = new StackPane(card, remove);
            wrapper.getStyleClass().add("viewer-area-card");
            StackPane.setAlignment(remove, Pos.TOP_RIGHT);
            cards.getChildren().add(wrapper);
        }
        // Explicit preferred height avoids a layout cycle with the overlay's maximum height.
        int cardHeight = areas.stream().mapToInt(area -> 130 + (area.failedChunks() > 0 ? 20 : 0)
                + (profile.allows(area.key().target()) ? 0 : 28)).sum();
        setPrefHeight(areas.isEmpty() ? 210 : 140 + cardHeight);
        footer.setText("本次会话 · 最近 " + profile.maxCachedScenes()
                + " 个区域\n容量不足时替换，退出程序清空。");
    }
}
