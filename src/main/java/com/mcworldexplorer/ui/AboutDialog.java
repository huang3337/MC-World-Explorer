package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.LegacyDataImporter;
import com.mcworldexplorer.update.AllowedExternalUrls;
import com.mcworldexplorer.update.AppVersion;
import com.mcworldexplorer.update.UpdateCheckClient;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-app modal cards for About, repository navigation cache confirmation, and first-run legacy data import.
 */
final class AboutDialog {
    static final String PROJECT_URL = AllowedExternalUrls.GITHUB_PROJECT_URL;
    static final String GITEE_LATEST_URL = AllowedExternalUrls.GITEE_LATEST_RELEASE_URL;
    static final String GITHUB_LATEST_URL = AllowedExternalUrls.GITHUB_LATEST_RELEASE_URL;
    static final String BILIBILI_URL = AllowedExternalUrls.BILIBILI_SPACE_URL;
    private static final String AUTHOR = "huang3337";
    private static final String BILIBILI_NAME = "驯鹿也不会寻路";
    private static final double AVATAR_SIZE = 48;
    private static final String DESCRIPTION = "无需启动 Minecraft，即可只读浏览 Java 版存档、二维地图与已加载区域的三维场景。";

    private AboutDialog() { }

    /**
     * Compatibility dialog wrapper retained for existing UI layout tests;
     * production UI presents {@link #createAboutCard} inside the main window modal overlay.
     */
    static Dialog<Void> create(Window owner, Consumer<String> openDocument) {
        var dialog = new Dialog<Void>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("关于 MC World Explorer");
        dialog.setResizable(true);
        VBox card = createAboutCard(
                openDocument,
                ignored -> { },
                () -> UpdateCheckClient.CheckResult.failed("离线测试环境"),
                (label, url) -> openDocument.accept(url),
                dialog::close,
                false);
        dialog.getDialogPane().setContent(card);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        return dialog;
    }

    static VBox createAboutCard(
            Consumer<String> openDocument,
            Consumer<String> onIgnoreVersion,
            Supplier<UpdateCheckClient.CheckResult> manualCheckRunner,
            BiConsumer<String, String> onRequestRepoConfirm,
            Runnable onClose) {
        return createAboutCard(openDocument, onIgnoreVersion,
                manualCheckRunner, onRequestRepoConfirm, onClose, true);
    }

    private static VBox createAboutCard(
            Consumer<String> openDocument,
            Consumer<String> onIgnoreVersion,
            Supplier<UpdateCheckClient.CheckResult> manualCheckRunner,
            BiConsumer<String, String> onRequestRepoConfirm,
            Runnable onClose,
            boolean includeFooterButtons) {
        Objects.requireNonNull(openDocument, "openDocument");
        Objects.requireNonNull(onIgnoreVersion, "onIgnoreVersion");
        Objects.requireNonNull(manualCheckRunner, "manualCheckRunner");
        Objects.requireNonNull(onRequestRepoConfirm, "onRequestRepoConfirm");
        Objects.requireNonNull(onClose, "onClose");

        var title = label("MC World Explorer");
        title.getStyleClass().add("world-name");
        String version = AppVersion.currentVersionString();
        var versionLabel = label("版本 " + version);
        versionLabel.setId("aboutVersion");
        versionLabel.getStyleClass().add("muted-text");

        var checkUpdateButton = new Button("检查更新");
        checkUpdateButton.setId("aboutCheckUpdateButton");
        var updateStatus = label("");
        updateStatus.setId("aboutUpdateStatus");
        updateStatus.getStyleClass().add("muted-text");
        updateStatus.managedProperty().bind(updateStatus.textProperty().isNotEmpty());
        updateStatus.visibleProperty().bind(updateStatus.managedProperty());

        var versionRow = new HBox(10, versionLabel, checkUpdateButton);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(versionLabel, Priority.ALWAYS);

        // Reserved empty slot: hidden on card open/close; expands with prominent banner only when an update is found.
        var updateBox = new VBox(8);
        updateBox.setId("aboutUpdateBox");
        updateBox.getStyleClass().add("update-alert-box");
        updateBox.setManaged(false);
        updateBox.setVisible(false);

        var status = label("");
        status.setId("aboutFeedback");
        status.getStyleClass().add("muted-text");
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());

        String[] giteeTargetUrl = { GITEE_LATEST_URL };
        String[] githubTargetUrl = { GITHUB_LATEST_URL };

        Consumer<UpdateCheckClient.CheckResult> renderUpdate = result -> {
            updateBox.getChildren().clear();
            giteeTargetUrl[0] = GITEE_LATEST_URL;
            githubTargetUrl[0] = GITHUB_LATEST_URL;
            if (result == null) {
                updateBox.setManaged(false);
                updateBox.setVisible(false);
                return;
            }
            if (result.status() == UpdateCheckClient.Status.UP_TO_DATE) {
                updateStatus.setText("当前已是最新版本（" + version + "）。");
                updateBox.setManaged(false);
                updateBox.setVisible(false);
                return;
            }
            if (result.status() == UpdateCheckClient.Status.UPDATE_AVAILABLE && result.release() != null) {
                var release = result.release();
                giteeTargetUrl[0] = release.giteeUrl();
                githubTargetUrl[0] = release.githubUrl();
                updateStatus.setText("");

                var heading = label("发现新版本 " + release.tagName());
                heading.setId("aboutUpdateTitle");
                heading.getStyleClass().add("update-alert-title");
                HBox.setHgrow(heading, Priority.ALWAYS);

                var ignoreBtn = new Button("忽略此版本");
                ignoreBtn.setId("aboutIgnoreUpdateButton");
                ignoreBtn.getStyleClass().add("quiet-button");
                ignoreBtn.setMinWidth(Region.USE_PREF_SIZE);
                ignoreBtn.setOnAction(e -> {
                    onIgnoreVersion.accept(release.version().normalized());
                    updateStatus.setText("已忽略版本 " + release.tagName() + " 的顶栏提醒。");
                    updateBox.getChildren().clear();
                    updateBox.setManaged(false);
                    updateBox.setVisible(false);
                });

                var headerRow = new HBox(8, heading, ignoreBtn);
                headerRow.setAlignment(Pos.CENTER_LEFT);

                var guide = label("请点击下方常驻的「国内镜像（Gitee）」或「GitHub」最新 Release 链接，前往发布页自行下载新版便携包（.zip）解压使用。");
                guide.setId("aboutUpdateGuide");
                guide.getStyleClass().add("update-alert-body");

                updateBox.getChildren().addAll(headerRow, guide);
                if (!release.summary().isBlank()) {
                    var summary = label(release.summary());
                    summary.getStyleClass().add("muted-text");
                    updateBox.getChildren().add(summary);
                }
                updateBox.setManaged(true);
                updateBox.setVisible(true);
                return;
            }
            updateStatus.setText(result.message());
            updateBox.setManaged(false);
            updateBox.setVisible(false);
        };

        checkUpdateButton.setOnAction(event -> {
            checkUpdateButton.setDisable(true);
            updateBox.getChildren().clear();
            updateBox.setManaged(false);
            updateBox.setVisible(false);
            updateStatus.setText("正在检查更新…");
            Thread worker = new Thread(() -> {
                UpdateCheckClient.CheckResult result = manualCheckRunner.get();
                Platform.runLater(() -> {
                    checkUpdateButton.setDisable(false);
                    renderUpdate.accept(result);
                });
            }, "manual-update-check");
            worker.setDaemon(true);
            worker.start();
        });

        var description = label(DESCRIPTION);

        // Permanent latest-release links for both Gitee mirror and GitHub repository.
        var giteeLabel = label("国内镜像最新 Release（Gitee · 免代理推荐）");
        giteeLabel.getStyleClass().add("detail-label");
        var giteeLink = new Hyperlink(GITEE_LATEST_URL);
        giteeLink.setId("aboutGiteeReleaseLink");
        giteeLink.getStyleClass().add("modal-link");
        giteeLink.setWrapText(true);
        giteeLink.setMaxWidth(Double.MAX_VALUE);
        giteeLink.setTooltip(new Tooltip("前往国内镜像仓库最新 Release 页面"));
        giteeLink.setOnAction(e -> onRequestRepoConfirm.accept("国内镜像仓库（Gitee 最新 Release）", giteeTargetUrl[0]));

        var githubLabel = label("GitHub 最新 Release");
        githubLabel.getStyleClass().add("detail-label");
        var githubLink = new Hyperlink(GITHUB_LATEST_URL);
        githubLink.setId("aboutGithubReleaseLink");
        githubLink.getStyleClass().add("modal-link");
        githubLink.setWrapText(true);
        githubLink.setMaxWidth(Double.MAX_VALUE);
        githubLink.setTooltip(new Tooltip("前往 GitHub 仓库最新 Release 页面"));
        githubLink.setOnAction(e -> onRequestRepoConfirm.accept("GitHub 最新 Release", githubTargetUrl[0]));

        var releaseLinksBox = new VBox(8,
                new VBox(2, giteeLabel, giteeLink),
                new VBox(2, githubLabel, githubLink));
        releaseLinksBox.setId("aboutReleaseLinksBox");
        releaseLinksBox.getStyleClass().add("modal-info-box");
        var releasesSection = new VBox(6, label("最新版本下载"), releaseLinksBox);

        var author = label("GitHub：" + AUTHOR);
        author.setId("aboutAuthor");

        var bilibiliPrefix = new Label("B站：");
        var bilibiliLink = new Hyperlink(BILIBILI_NAME);
        bilibiliLink.setId("aboutBilibiliLink");
        bilibiliLink.getStyleClass().add("modal-link");
        bilibiliLink.setTooltip(new Tooltip("在默认浏览器中打开作者 B站主页"));
        bilibiliLink.setOnAction(event -> {
            try {
                openDocument.accept(AllowedExternalUrls.requireAllowed(BILIBILI_URL));
                status.setText("已请求在默认浏览器中打开作者 B站主页。");
            } catch (RuntimeException failure) {
                status.setText("无法打开浏览器，请手动访问：" + BILIBILI_URL);
            }
        });
        var bilibiliRow = new HBox(2, bilibiliPrefix, bilibiliLink);
        bilibiliRow.setId("aboutBilibili");
        bilibiliRow.setAlignment(Pos.CENTER_LEFT);

        var authorDetails = new VBox(5, author, bilibiliRow);
        authorDetails.setMinWidth(0);
        HBox.setHgrow(authorDetails, Priority.ALWAYS);
        var authorRow = new HBox(12, avatar(), authorDetails);
        authorRow.setAlignment(Pos.CENTER_LEFT);
        var authorSection = new VBox(8, label("作者"), authorRow);

        var license = label("许可证：GNU GPL v3.0");
        license.getStyleClass().add("muted-text");

        var project = new Hyperlink(PROJECT_URL);
        project.setId("aboutProjectLink");
        project.getStyleClass().add("modal-link");
        project.setWrapText(true);
        project.setMaxWidth(Double.MAX_VALUE);
        project.setTooltip(new Tooltip("在默认浏览器中打开项目主页"));
        project.setOnAction(event -> {
            try {
                openDocument.accept(AllowedExternalUrls.requireAllowed(PROJECT_URL));
                status.setText("已请求在默认浏览器中打开项目主页。");
            } catch (RuntimeException failure) {
                status.setText("无法打开浏览器，请复制工具信息中的项目地址后访问。");
            }
        });

        var content = new VBox(12,
                new VBox(6, title, versionRow, updateStatus, updateBox),
                description,
                releasesSection,
                new Separator(),
                authorSection,
                license,
                new VBox(3, label("项目主页"), project),
                status);
        content.setPadding(new Insets(20));
        content.setId("aboutContent");
        content.setFocusTraversable(false);

        var scroll = new ScrollPane(content);
        scroll.setId("aboutScrollPane");
        scroll.getStyleClass().addAll("modal-scroll", "about-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.setPrefViewportWidth(500);
        scroll.setMaxHeight(480);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        var card = new VBox(0, scroll);
        card.setId("aboutModalCard");
        card.getStyleClass().add("modal-card");
        card.setMaxWidth(540);
        card.setMaxHeight(600);
        card.setFocusTraversable(false);

        if (includeFooterButtons) {
            var copyButton = new Button("复制工具信息");
            copyButton.setId("aboutCopyButton");
            copyButton.setOnAction(event -> {
                try {
                    var data = new ClipboardContent();
                    data.putString("MC World Explorer\n版本：" + version + "\n作者（GitHub）：" + AUTHOR
                            + "\nB站：" + BILIBILI_NAME + "（" + BILIBILI_URL + "）"
                            + "\n" + DESCRIPTION
                            + "\n国内镜像最新 Release：" + GITEE_LATEST_URL
                            + "\nGitHub 最新 Release：" + GITHUB_LATEST_URL
                            + "\n项目主页：" + PROJECT_URL
                            + "\n许可证：GNU GPL v3.0");
                    if (!Clipboard.getSystemClipboard().setContent(data)) {
                        throw new IllegalStateException("剪贴板不可用");
                    }
                    status.setText("已复制工具信息与发布页地址。");
                } catch (RuntimeException failure) {
                    status.setText("复制失败，请稍后重试。");
                }
            });
            var closeButton = new Button("关闭");
            closeButton.setId("aboutCloseButton");
            closeButton.getStyleClass().add("primary-action");
            closeButton.setOnAction(e -> onClose.run());

            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            var footer = new HBox(10, copyButton, spacer, closeButton);
            footer.setAlignment(Pos.CENTER_RIGHT);
            footer.setPadding(new Insets(12, 20, 16, 20));
            card.getChildren().addAll(new Separator(), footer);
        }
        return card;
    }

    static VBox createRepoConfirmCard(
            String repoLabel,
            String targetUrl,
            Runnable onBack,
            Consumer<String> onConfirmOpen) {
        String safeUrl = AllowedExternalUrls.requireAllowed(targetUrl);
        var title = label("前往 " + repoLabel + " 前的保留提示");
        title.getStyleClass().add("section-title");

        var details = label(
                "升级新版本时，建议保留当前旧版目录以便一键迁移数据：\n"
                        + "• 个人设置、自定义地点标记、连线与已装插件保存在当前目录的 config/ 文件夹；\n"
                        + "• 已扫描的二维地图瓦片保存在当前目录的 cache/ 文件夹。\n\n"
                        + "建议将下载的新版 ZIP 解压到新的文件夹，暂时保留旧版目录。首次启动新版时，软件会自动弹出「导入旧版数据」卡片，可一键将旧版数据安全复制过去。");
        var infoBox = new VBox(8, details);
        infoBox.getStyleClass().add("modal-info-box");

        var urlHint = label("即将打开：" + safeUrl);
        urlHint.getStyleClass().add("muted-text");

        var backButton = new Button("返回");
        backButton.setId("repoConfirmBackButton");
        backButton.setOnAction(e -> onBack.run());

        var confirmButton = new Button("确认并前往仓库");
        confirmButton.setId("repoConfirmOpenButton");
        confirmButton.getStyleClass().add("primary-action");
        confirmButton.setOnAction(e -> onConfirmOpen.accept(safeUrl));

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var footer = new HBox(10, backButton, spacer, confirmButton);
        footer.setAlignment(Pos.CENTER_RIGHT);

        var card = new VBox(14, title, infoBox, urlHint, footer);
        card.setId("repoConfirmModalCard");
        card.getStyleClass().add("modal-card");
        card.setPadding(new Insets(20));
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setFocusTraversable(false);
        return card;
    }

    static VBox createFirstRunImportCard(
            Window owner,
            Consumer<LegacyDataImporter.ImportResult> onImported,
            Runnable onSkip) {
        var title = label("导入旧版数据");
        title.getStyleClass().add("section-title");

        var intro = label("检测到这是首次打开当前便携包。如果您之前使用过旧版 MC World Explorer，可以选择旧版根目录，将原有个人数据安全复制到当前目录（不会删除或修改旧目录原文件）：");

        var configCheck = new CheckBox("个人设置、自定义地点标记与连线、已装插件（config）");
        configCheck.setId("importConfigCheck");
        configCheck.setSelected(true);
        configCheck.setWrapText(true);

        var cacheCheck = new CheckBox("已扫描的二维地图缓存（cache）");
        cacheCheck.setId("importCacheCheck");
        cacheCheck.setSelected(true);
        cacheCheck.setWrapText(true);

        var optionsBox = new VBox(10, configCheck, cacheCheck);
        optionsBox.getStyleClass().add("modal-info-box");

        var feedback = label("");
        feedback.setId("firstRunImportFeedback");
        feedback.getStyleClass().add("field-error");
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
        feedback.visibleProperty().bind(feedback.managedProperty());

        var skipButton = new Button("我是新用户 / 跳过");
        skipButton.setId("firstRunSkipButton");
        skipButton.setOnAction(e -> onSkip.run());

        var chooseButton = new Button("选择旧版目录并导入…");
        chooseButton.setId("firstRunChooseImportButton");
        chooseButton.getStyleClass().add("primary-action");
        chooseButton.setOnAction(e -> {
            if (!configCheck.isSelected() && !cacheCheck.isSelected()) {
                feedback.setText("请至少勾选一项要导入的数据，或点击跳过。");
                return;
            }
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择旧版 MC World Explorer 根目录");
            File chosen = chooser.showDialog(owner);
            if (chosen == null) {
                return;
            }
            Path sourceRoot = chosen.toPath().toAbsolutePath().normalize();
            var options = new LegacyDataImporter.Options(configCheck.isSelected(), cacheCheck.isSelected());
            chooseButton.setDisable(true);
            skipButton.setDisable(true);
            feedback.getStyleClass().remove("field-error");
            feedback.setText("正在安全复制旧版数据，请稍候…");
            Thread worker = new Thread(() -> {
                try {
                    var result = LegacyDataImporter.importFrom(sourceRoot, options);
                    Platform.runLater(() -> onImported.accept(result));
                } catch (IOException | RuntimeException failure) {
                    Platform.runLater(() -> {
                        chooseButton.setDisable(false);
                        skipButton.setDisable(false);
                        if (!feedback.getStyleClass().contains("field-error")) {
                            feedback.getStyleClass().add("field-error");
                        }
                        feedback.setText("导入失败：" + failure.getMessage());
                    });
                }
            }, "legacy-data-importer");
            worker.setDaemon(true);
            worker.start();
        });

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var footer = new HBox(10, skipButton, spacer, chooseButton);
        footer.setAlignment(Pos.CENTER_RIGHT);

        var card = new VBox(14, title, intro, optionsBox, feedback, footer);
        card.setId("firstRunImportModalCard");
        card.getStyleClass().add("modal-card");
        card.setPadding(new Insets(20));
        card.setMaxWidth(540);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setFocusTraversable(false);
        return card;
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
}
