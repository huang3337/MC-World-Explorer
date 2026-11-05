package com.mcworldexplorer.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.io.IOException;
import java.util.Properties;
import java.util.function.Consumer;

/** Product information, available even when no world has been selected. */
final class AboutDialog {
    private static final String PROJECT_URL = "https://github.com/huang3337/MC-World-Explorer";
    private static final String AUTHOR = "huang3337";
    private static final String BILIBILI_NAME = "驯鹿也不会寻路";
    private static final double AVATAR_SIZE = 48;
    private static final String DESCRIPTION = "无需启动 Minecraft，即可只读浏览 Java 版存档、二维地图与已加载区域的三维场景。";

    private AboutDialog() { }

    static Dialog<Void> create(Window owner, Consumer<String> openDocument) {
        var dialog = new Dialog<Void>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("关于 MC World Explorer");
        dialog.setResizable(true);

        var title = label("MC World Explorer");
        title.getStyleClass().add("world-name");
        String version = version();
        var versionLabel = label("版本 " + version);
        versionLabel.setId("aboutVersion");
        versionLabel.getStyleClass().add("muted-text");
        var description = label(DESCRIPTION);
        var author = label("GitHub：" + AUTHOR);
        author.setId("aboutAuthor");
        var bilibili = label("B站：" + BILIBILI_NAME);
        bilibili.setId("aboutBilibili");
        var authorDetails = new VBox(5, author, bilibili);
        authorDetails.setMinWidth(0);
        HBox.setHgrow(authorDetails, Priority.ALWAYS);
        var authorRow = new HBox(12, avatar(), authorDetails);
        authorRow.setAlignment(Pos.CENTER_LEFT);
        var authorSection = new VBox(8, label("作者"), authorRow);
        var license = label("许可证：GNU GPL v3.0");
        license.getStyleClass().add("muted-text");
        var project = new Hyperlink(PROJECT_URL);
        project.setId("aboutProjectLink");
        project.setWrapText(true);
        project.setMaxWidth(Double.MAX_VALUE);
        project.setTooltip(new Tooltip("在默认浏览器中打开项目主页"));
        var status = label("");
        status.setId("aboutFeedback");
        status.getStyleClass().add("muted-text");
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());
        project.setOnAction(event -> {
            try {
                openDocument.accept(PROJECT_URL);
                status.setText("已请求在默认浏览器中打开项目主页。");
            } catch (RuntimeException failure) {
                status.setText("无法打开浏览器，请复制工具信息中的项目地址后访问。");
            }
        });

        var content = new VBox(12, new VBox(4, title, versionLabel), description,
                new Separator(), authorSection, license, new VBox(3, label("项目主页"), project), status);
        content.setPadding(new Insets(18));
        content.setPrefWidth(480);
        content.setMinWidth(320);
        content.setId("aboutContent");
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(480);
        scroll.setPrefViewportHeight(400);
        scroll.setMinViewportHeight(140);
        dialog.getDialogPane().setContent(scroll);
        var stylesheet = AboutDialog.class.getResource("/css/styles.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());

        var copy = new ButtonType("复制工具信息", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(copy, ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(copy).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                var data = new ClipboardContent();
                data.putString("MC World Explorer\n版本：" + version + "\n作者（GitHub）：" + AUTHOR + "\nB站：" + BILIBILI_NAME
                        + "\n" + DESCRIPTION + "\n项目主页：" + PROJECT_URL + "\n许可证：GNU GPL v3.0");
                if (!Clipboard.getSystemClipboard().setContent(data)) throw new IllegalStateException("剪贴板不可用");
                status.setText("已复制工具信息与项目地址。");
            } catch (RuntimeException failure) {
                status.setText("复制失败，请稍后重试。");
            }
        });
        return dialog;
    }

    private static Label label(String text) {
        var label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static StackPane avatar() {
        var frame = new StackPane();
        frame.setId("aboutAvatar");
        double frameSize = AVATAR_SIZE + 2;
        frame.setMinSize(frameSize, frameSize);
        frame.setPrefSize(frameSize, frameSize);
        frame.setMaxSize(frameSize, frameSize);
        frame.getStyleClass().add("about-avatar");
        frame.setMouseTransparent(true);
        frame.setFocusTraversable(false);
        frame.setAccessibleText("作者头像");
        try (var stream = AboutDialog.class.getResourceAsStream("/images/author-avatar.jpg")) {
            if (stream != null) {
                // Keep double-resolution pixels for high-DPI display; only the view is clipped.
                var image = new Image(stream, AVATAR_SIZE * 2, AVATAR_SIZE * 2, true, true);
                if (!image.isError() && image.getWidth() > 0 && image.getHeight() > 0) {
                    var view = new ImageView(image);
                    view.setFitWidth(AVATAR_SIZE);
                    view.setFitHeight(AVATAR_SIZE);
                    view.setPreserveRatio(true);
                    view.setSmooth(true);
                    view.setClip(new Circle(AVATAR_SIZE / 2, AVATAR_SIZE / 2, AVATAR_SIZE / 2));
                    frame.getChildren().add(view);
                    return frame;
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            // The supplied portrait is optional; keep the author details usable without it.
        }
        var unavailable = label("头像\n不可用");
        unavailable.setStyle("-fx-font-size: 10px; -fx-text-alignment: center;");
        frame.getChildren().add(unavailable);
        return frame;
    }

    private static String version() {
        try (var stream = AboutDialog.class.getResourceAsStream("/mcwe-build.properties")) {
            if (stream != null) {
                var properties = new Properties();
                properties.load(stream);
                String version = properties.getProperty("version", "").strip();
                if (!version.isEmpty()) return version;
            }
        } catch (IOException | IllegalArgumentException failure) {
            // Missing or invalid metadata must not invent a release version or prevent opening About.
        }
        return "信息不可用";
    }
}
