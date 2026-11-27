package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapTileScheduler;
import com.mcworldexplorer.map.MapViewportState;
import com.mcworldexplorer.map.SurfaceRangeExport;
import com.mcworldexplorer.map.ViewportExporter;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.world.WorldInfo;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

final class SurfaceExportDialog {
    private final Dialog<Void> dialog = new Dialog<>();
    private final GridPane corners = new GridPane();
    private final Label status = new Label();
    private final Button start;
    private final Button cancel;
    private final WorldInfo world;
    private final WorldDimension dimension;
    private final MapTileScheduler scheduler;
    private final ViewportExporter exporter;
    private final Consumer<String> onSaved;
    private final TextField firstCorner, secondCorner;
    private SurfaceRangeExport range;
    private Task<?> active;
    private Path savedFile;
    private boolean saving;

    SurfaceExportDialog(Window owner, WorldInfo world, WorldDimension dimension,
                        MapViewportState view, double width, double height, MapTileScheduler scheduler,
                        ViewportExporter exporter, ExportFolders folders, Consumer<String> onSaved) {
        this.world = world;
        this.dimension = dimension;
        this.scheduler = scheduler;
        this.exporter = exporter;
        this.onSaved = onSaved;
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("指定范围导出地表 PNG");
        dialog.setHeaderText(world.getLevelName() + " · " + dimension);
        var pane = dialog.getDialogPane();
        var export = new ButtonType("导出", ButtonBar.ButtonData.OK_DONE);
        var openFolder = new ButtonType("打开文件夹", ButtonBar.ButtonData.LEFT);
        pane.getButtonTypes().addAll(export, ButtonType.CANCEL, openFolder);
        var folderButton = (Button) pane.lookupButton(openFolder);
        folderButton.setId("rangeExportFolder");
        folderButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Path directory = savedFile == null ? PortablePaths.exportsDirectory() : savedFile.getParent();
            folders.show(pane.getScene().getWindow(), directory, world.getFolderPath());
        });
        start = (Button) pane.lookupButton(export);
        cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancel.setText("取消");
        corners.setHgap(10);
        corners.setVgap(6);
        firstCorner = field("firstCorner", Math.floor(view.worldXAt(0, width)), Math.floor(view.worldZAt(0, height)));
        secondCorner = field("secondCorner", Math.ceil(view.worldXAt(width, width)) - 1,
                Math.ceil(view.worldZAt(height, height)) - 1);
        corners.addRow(0, new Label("第一角"), firstCorner);
        corners.addRow(1, new Label("第二角"), secondCorner);
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setId("rangeExportStatus");
        pane.setPrefWidth(430);
        pane.setContent(new VBox(10, new Label("包含两端方块 · 1 方块 / 像素 · 宽高各限 "
                + SurfaceRangeExport.MAX_SIDE), new Label("在地图上右键复制坐标，再粘贴到对应的角。"), corners, status));
        status.textProperty().addListener((obs, before, after) -> resizeToContent());
        for (var field : new TextField[]{firstCorner, secondCorner}) {
            field.textProperty().addListener((obs, before, after) -> validate());
        }
        validate();
        start.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (range != null && active == null) render(range);
        });
        dialog.setOnCloseRequest(event -> { if (saving) event.consume(); });
        dialog.setOnHidden(event -> {
            if (active != null && !saving) {
                active.cancel(false);
                scheduler.cancelIndex();
            }
        });
    }

    private static TextField field(String id, double x, double z) {
        var field = new TextField(BlockCoordinates.format((long) x, (long) z));
        field.setId(id);
        field.setPromptText("X, Z");
        GridPane.setHgrow(field, Priority.ALWAYS);
        // 整对坐标粘贴时替换该角的预填值，逐字编辑仍使用正常的光标和选区。
        field.setTextFormatter(new TextFormatter<>(change -> {
            String inserted = change.getText();
            if (BlockCoordinates.isPair(inserted)) {
                change.setRange(0, change.getControlText().length());
                change.selectRange(inserted.length(), inserted.length());
            }
            return change;
        }));
        return field;
    }

    private void validate() {
        range = null;
        try {
            var first = BlockCoordinates.parse(firstCorner.getText());
            var second = BlockCoordinates.parse(secondCorner.getText());
            range = SurfaceRangeExport.fromCorners(first.x(), first.z(), second.x(), second.z());
            status.setText("输出尺寸：" + range.width() + " × " + range.height() + " 像素");
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
        }
        start.setDisable(range == null);
    }

    private void render(SurfaceRangeExport request) {
        corners.setDisable(true);
        start.setDisable(true);
        status.setText("正在读取指定范围的地表…");
        var task = new Task<BufferedImage>() {
            @Override protected BufferedImage call() throws IOException {
                world.getFolderPath().toRealPath();
                return request.render(dimension, this::isCancelled);
            }
        };
        task.setOnSucceeded(event -> {
            if (dialog.isShowing()) save(task.getValue());
        });
        submit(task);
    }

    private void save(BufferedImage image) {
        saving = true;
        cancel.setDisable(true);
        status.setText("正在保存 PNG…");
        var task = new Task<Path>() {
            @Override protected Path call() throws IOException {
                return exporter.exportToDefault(image, world.getLevelName() + "-地表", world.getFolderPath());
            }
        };
        task.setOnSucceeded(event -> {
            active = null;
            saving = false;
            if (!dialog.isShowing()) return;
            savedFile = task.getValue();
            Path relative = PortablePaths.applicationRoot().relativize(savedFile);
            start.setVisible(false);
            start.setManaged(false);
            cancel.setDisable(false);
            cancel.setText("关闭");
            status.setText("已导出指定范围地表\n" + relative);
            onSaved.accept("已导出指定范围地表：" + relative);
        });
        submit(task);
    }

    private void submit(Task<?> task) {
        active = task;
        task.setOnFailed(event -> failed(task.getException()));
        if (!scheduler.submitIndex(token -> task.run(), ignored -> { })) {
            failed(new IOException("地图后台队列已满或已关闭，请稍后重试"));
        }
    }

    private void failed(Throwable error) {
        active = null;
        saving = false;
        corners.setDisable(false);
        cancel.setDisable(false);
        start.setDisable(false);
        status.setText("导出失败：" + error.getMessage());
    }

    private void resizeToContent() {
        if (!dialog.isShowing()) return;
        var pane = dialog.getDialogPane();
        pane.applyCss();
        pane.setPrefHeight(Region.USE_COMPUTED_SIZE);
        // 有换行的 Label 需要以小窗实际宽度测量，不能使用无限宽时的单行高度。
        pane.setPrefHeight(Math.ceil(pane.prefHeight(pane.getWidth())));
        pane.getScene().getWindow().sizeToScene();
    }

    void show() { dialog.show(); }
    boolean focusIfOpen() {
        if (!dialog.isShowing()) return false;
        dialog.getDialogPane().getScene().getWindow().requestFocus();
        return true;
    }
    void close() {
        dialog.setOnCloseRequest(null);
        dialog.close();
    }
}
