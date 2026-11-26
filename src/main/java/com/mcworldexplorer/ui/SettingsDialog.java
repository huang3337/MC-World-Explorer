package com.mcworldexplorer.ui;

import com.mcworldexplorer.ClientRuntime;
import com.mcworldexplorer.WindowsGraphics;
import com.mcworldexplorer.map.MapTileScheduler;
import com.mcworldexplorer.storage.LegacyDataImporter;
import com.mcworldexplorer.storage.PortableSettings;
import com.mcworldexplorer.storage.RuntimePreferences;
import com.mcworldexplorer.viewer.ViewerPerformanceProfile;
import com.mcworldexplorer.viewer.lwjgl.LwjglViewerWindow;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

final class SettingsDialog {
    static void show(Stage owner, PortableSettings store, MapViewerController map, Path root, boolean scanning,
                     Consumer<Path> useDirectory) throws IOException {
        show(owner, store, map, root, scanning, useDirectory, () -> { });
    }

    static void show(Stage owner, PortableSettings store, MapViewerController map, Path root, boolean scanning,
                     Consumer<Path> useDirectory, Runnable onLegacyImported) throws IOException {
        var prefs = store.loadRuntimePreferences();
        boolean checkUpdatesEnabled = store.isCheckUpdatesOnStartup();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("设置");
        dialog.setResizable(true);
        var save = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        var restart = new ButtonType("保存并重启 MCWE", ButtonBar.ButtonData.OTHER);
        var pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(restart, save, ButtonType.CANCEL);
        var error = note("");
        error.setId("settingsFeedback");
        error.getStyleClass().add("field-error");
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        var pipeline = choice("mapPipeline", List.<WindowsGraphics.Adapter>of(), -1);
        String currentDevice = MapRenderDevice.read(owner.getTitle()).description();
        pipeline.setPromptText("选择显卡型号");
        pipeline.setDisable(true);
        var actual = note("当前二维设备：" + currentDevice);
        actual.setId("activeMapDevice");
        var sceneDevice = note("最近一次三维设备：" + LwjglViewerWindow.lastDevice());
        sceneDevice.setId("actualSceneDevice");
        var hardware = note("");
        hardware.setId("graphicsAdapters");
        VBox render = section(labelFor("二维地图 · 显卡型号", pipeline), pipeline, actual, hardware);
        if (ClientRuntime.externalMapPipeline()) {
            hardware.setText("启动参数已指定二维后端。");
        } else if (!WindowsGraphics.supported()) {
            hardware.setText("请从 Windows 便携包或 run-app.bat 启动以选择显卡。");
        } else {
            hardware.setText("正在检测本机显卡…");
            javafx.concurrent.Task<List<WindowsGraphics.Adapter>> query = new javafx.concurrent.Task<>() {
                @Override protected List<WindowsGraphics.Adapter> call() throws IOException { return WindowsGraphics.adapters(); }
            };
            query.setOnSucceeded(e -> {
                var models = query.getValue();
                pipeline.getItems().setAll(models);
                pipeline.setDisable(models.isEmpty());
                var saved = models.stream().filter(model -> model.id().equals(prefs.mapAdapter())).findFirst();
                saved.ifPresent(pipeline::setValue);
                if (models.isEmpty()) hardware.setText("没有检测到可选择的实体显卡。");
                else if (!prefs.mapAdapter().isEmpty() && saved.isEmpty()) hardware.setText("上次选择的显卡已断开，可重新选择。");
                else hardware.setText("保存并重启 MCWE 后生效。");
            });
            query.setOnFailed(e -> hardware.setText("未能读取本机显卡列表，可重新打开设置重试。"));
            Thread reader = new Thread(query, "graphics-inventory");
            reader.setDaemon(true);
            reader.start();
        }
        Label sceneTitle = new Label("三维预览 · OpenGL");
        sceneTitle.getStyleClass().add("section-title");
        render.getChildren().addAll(new Separator(), sceneTitle, sceneDevice,
                note("打开预览时由系统 OpenGL 驱动选择设备。"));
        var heap = choice("heapLimit", RuntimePreferences.HEAP_OPTIONS, RuntimePreferences.HEAP_OPTIONS.indexOf(prefs.heapMiB()));
        heap.setConverter(new StringConverter<>() {
            public String toString(Integer value) { return value == null || value == 0 ? "JVM 自动" : value / 1024 + " GiB"; }
            public Integer fromString(String unused) { throw new UnsupportedOperationException(); }
        });
        var workers = choice("scanWorkers", List.of(1, 2), prefs.scanWorkers() - 1);
        String override = System.getProperty(MapTileScheduler.WORKER_PROPERTY, "");
        workers.setDisable(override.equals("1") || override.equals("2"));
        var profile = choice("sceneProfile", List.of(ViewerPerformanceProfile.values()), map.currentProfile().ordinal());
        profile.setDisable(map.profileBusy());
        Label budget = note("");
        Runnable describeBudget = () -> {
            var p = profile.getValue();
            budget.setText(p.maxCachedScenes() + " 个场景，共 " + (p.maxCachedBytes() >> 20) + " MiB 估算预算；最多 "
                    + p.maxTargetChunks() + " 个选区区块。保存后按最近使用顺序回收超额缓存。");
        };
        describeBudget.run();
        profile.valueProperty().addListener((obs, old, selected) -> describeBudget.run());
        VBox memory = section(labelFor("JVM 最大堆", heap), heap,
                note("当前最大堆 " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB，修改后需重启。进程峰值还包含原生内存、线程和驱动分配。"),
                labelFor("同时扫描的二维瓦片数", workers), workers,
                note("当前 " + map.scanWorkers() + " 路。" + (workers.isDisabled() ? "启动参数已指定扫描并发。" : "保存后生效，已开始的扫描可完成。")),
                labelFor("三维选区与场景缓存", profile), profile, budget);
        var theme = choice("appearance", List.of("浅色", "深色"), map.darkTheme() ? 1 : 0);
        var directory = new TextField(root == null ? "" : root.toString());
        directory.setEditable(false);
        Button browse = new Button("选择存档目录…");
        browse.setDisable(scanning);
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择 Minecraft 目录");
            if (!directory.getText().isEmpty() && Files.isDirectory(Path.of(directory.getText())))
                chooser.setInitialDirectory(new File(directory.getText()));
            File chosen = chooser.showDialog(pane.getScene().getWindow());
            if (chosen != null) directory.setText(chosen.toPath().toAbsolutePath().normalize().toString());
        });
        var checkUpdates = new CheckBox("启动时自动检查更新");
        checkUpdates.setId("checkUpdatesOnStartup");
        checkUpdates.setSelected(checkUpdatesEnabled);
        Button importLegacy = new Button("从旧版目录导入数据…");
        importLegacy.setId("importLegacyDataButton");
        importLegacy.setDisable(scanning);
        Label importNote = note("从旧版 MC World Explorer 目录只读复制设置、地点标记、插件与地图缓存。");
        importLegacy.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择旧版 MC World Explorer 根目录");
            File chosen = chooser.showDialog(pane.getScene().getWindow());
            if (chosen == null) return;
            try {
                var result = LegacyDataImporter.importFrom(
                        chosen.toPath().toAbsolutePath().normalize(), LegacyDataImporter.Options.ALL);
                var importedPrefs = store.loadRuntimePreferences();
                boolean importedDark = store.usesDarkTheme();
                var importedProfile = store.loadViewerPerformanceProfile()
                        .map(ViewerPerformanceProfile::fromSetting)
                        .orElse(map.currentProfile());
                heap.getSelectionModel().select(
                        Math.max(0, RuntimePreferences.HEAP_OPTIONS.indexOf(importedPrefs.heapMiB())));
                workers.getSelectionModel().select(Math.max(0, importedPrefs.scanWorkers() - 1));
                profile.setValue(importedProfile);
                theme.getSelectionModel().select(importedDark ? 1 : 0);
                store.loadCustomSavesPath().ifPresent(p -> directory.setText(p.toString()));
                checkUpdates.setSelected(store.isCheckUpdatesOnStartup());
                if (!pipeline.getItems().isEmpty()) {
                    pipeline.getItems().stream()
                            .filter(model -> model.id().equals(importedPrefs.mapAdapter()))
                            .findFirst()
                            .ifPresentOrElse(pipeline::setValue, () -> pipeline.setValue(null));
                }
                importNote.setText("已导入 " + result.configFilesCopied() + " 项配置与 " + result.cacheFilesCopied() + " 项缓存。");
                error.setText("");
                onLegacyImported.run();
            } catch (IOException | RuntimeException failure) {
                error.setText("导入失败：" + failure.getMessage());
            }
        });
        Button resources = new Button("资源与插件…");
        resources.setOnAction(event -> map.openResourceSettings(false));
        VBox tools = section(labelFor("界面外观", theme), theme, labelFor("存档库目录", directory),
                directory, browse, new Separator(), checkUpdates, importLegacy, importNote,
                new Separator(), resources, note("查看当前存档的客户端资源、实例绑定和已安装插件。"));
        var tabs = new TabPane(tab("渲染", render), tab("内存", memory), tab("常用", tools));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var content = new VBox(8, tabs, error);
        if (!ClientRuntime.startupNotice().isEmpty()) content.getChildren().add(note(ClientRuntime.startupNotice()));
        pane.setContent(content);
        pane.setPrefWidth(530);
        java.util.function.Supplier<RuntimePreferences> selection = () -> new RuntimePreferences(
                pipeline.getValue() == null ? prefs.mapMode() : RuntimePreferences.MapMode.AUTO,
                heap.getValue(), workers.getValue(),
                pipeline.getValue() == null ? prefs.mapAdapter() : pipeline.getValue().id());
        Runnable updateRestart = () -> pane.lookupButton(restart).setDisable(
                !ClientRuntime.restartNeeded(selection.get()));
        pipeline.valueProperty().addListener((o, old, value) -> updateRestart.run());
        heap.valueProperty().addListener((o, old, value) -> updateRestart.run());
        updateRestart.run();
        javafx.event.EventHandler<ActionEvent> apply = event -> {
            boolean dark = theme.getSelectionModel().getSelectedIndex() == 1;
            var selected = selection.get();
            Path chosen = directory.getText().isEmpty() ? null : Path.of(directory.getText());
            Path changed = java.util.Objects.equals(root, chosen) ? null : chosen;
            try {
                store.savePreferences(selected, dark, profile.getValue().settingValue(), changed, checkUpdates.isSelected());
            } catch (IOException failure) {
                error.setText("保存失败：" + failure.getMessage());
                event.consume();
                return;
            }
            map.applySavedPreferences(selected.scanWorkers(), dark, profile.getValue());
            if (changed != null) useDirectory.accept(changed);
            if (event.getSource() == pane.lookupButton(restart)) {
                try {
                    ClientRuntime.restart(selected);
                    javafx.application.Platform.runLater(javafx.application.Platform::exit);
                } catch (IOException failure) {
                    error.setText("设置已保存，请手动重启 MCWE：" + failure.getMessage());
                    event.consume();
                }
            }
        };
        pane.lookupButton(save).addEventFilter(ActionEvent.ACTION, apply);
        pane.lookupButton(restart).addEventFilter(ActionEvent.ACTION, apply);
        dialog.showAndWait();
    }

    private static Tab tab(String name, VBox content) {
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(370);
        return new Tab(name, scroll);
    }

    private static VBox section(Node... rows) {
        var box = new VBox(8, rows);
        box.setPadding(new Insets(14));
        return box;
    }

    private static Label labelFor(String name, Node control) {
        Label label = new Label(name);
        label.setLabelFor(control);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    private static <T> ComboBox<T> choice(String id, List<T> values, int selected) {
        var box = new ComboBox<T>();
        box.setId(id);
        box.getItems().setAll(values);
        box.getSelectionModel().select(selected);
        return box;
    }
}
