package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.PortableSettings;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

final class WorkspaceAppearance {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceAppearance.class);
    private static final String BASE = stylesheet("styles");
    private static final String DARK = stylesheet("dark");

    private static String stylesheet(String name) {
        return Objects.requireNonNull(WorkspaceAppearance.class.getResource("/css/" + name + ".css")).toExternalForm();
    }

    static void install(Node host, RadioMenuItem light, RadioMenuItem dark, PortableSettings settings) {
        boolean initiallyDark = false;
        try {
            initiallyDark = settings.usesDarkTheme();
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("Could not read appearance preference", failure);
        }
        (initiallyDark ? dark : light).setSelected(true);
        attachSceneHost(host, dark);
        light.setOnAction(event -> select(host.getScene(), light, false, settings));
        dark.setOnAction(event -> select(host.getScene(), dark, true, settings));
    }

    /**
     * Attaches stylesheet initialization to a top-level root node so {@code styles.css} is registered
     * when {@code new Scene(root)} runs, before {@code SplitPaneSkin} attaches nested children mid-CSS-pass.
     */
    static void attachSceneHost(Node host, RadioMenuItem dark) {
        if (host == null) return;
        java.util.function.Consumer<Scene> configureScene = scene -> {
            if (scene == null) return;
            if (!scene.getStylesheets().contains(BASE)) scene.getStylesheets().add(BASE);
            // Owned JavaFX dialogs follow the scene's stylesheets, including live changes.
            apply(scene, dark.isSelected());
            var available = Font.getFamilies();
            for (String family : List.of("Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei")) {
                if (available.contains(family)) {
                    String fontStyle = "-fx-font-family: '" + family + "';";
                    if (!fontStyle.equals(scene.getRoot().getStyle())) {
                        scene.getRoot().setStyle(fontStyle);
                    }
                    break;
                }
            }
        };
        if (host.getScene() != null) {
            configureScene.accept(host.getScene());
        }
        host.sceneProperty().addListener((observable, previous, scene) -> configureScene.accept(scene));
    }

    private static void select(Scene scene, RadioMenuItem selected, boolean dark, PortableSettings settings) {
        selected.setSelected(true);
        apply(scene, dark);
        try {
            settings.saveDarkTheme(dark);
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("Could not save appearance preference", failure);
            var alert = new Alert(Alert.AlertType.WARNING, "本次外观已切换，但设置未能保存。");
            alert.initOwner(scene.getWindow());
            alert.show();
        }
    }

    static void apply(Scene scene, boolean dark) {
        var sheets = scene.getStylesheets();
        if (dark && !sheets.contains(DARK)) sheets.add(DARK);
        if (!dark) sheets.remove(DARK);
    }
}
