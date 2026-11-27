package com.mcworldexplorer.ui;

import com.mcworldexplorer.update.AllowedExternalUrls;
import com.mcworldexplorer.update.AppVersion;
import com.mcworldexplorer.update.UpdateCheckClient;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Contract checks for V0.7.9 in-app modal overlay, permanent latest release links, transient update banner, and TLS packaging. */
class ModalOverlayContractTest {

    private static void ensureJavaFxToolkit() throws InterruptedException {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                latch.countDown();
            });
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (IllegalStateException alreadyInitialized) {
            // Toolkit is already running in this test JVM.
        }
    }

    private static <T> T onFxThread(java.util.concurrent.Callable<T> task) throws Exception {
        ensureJavaFxToolkit();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            if (error.get() instanceof Exception ex) throw ex;
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    @Test
    void mainFxmlUsesStackPaneRootWithModalOverlayAndUpdateBadge() throws Exception {
        String fxml;
        try (var stream = getClass().getResourceAsStream("/fxml/main.fxml")) {
            assertNotNull(stream);
            fxml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(fxml.contains("<StackPane fx:id=\"appRoot\""));
        assertTrue(fxml.contains("fx:id=\"modalOverlay\""));
        assertTrue(fxml.contains("<StackPane alignment=\"CENTER\" maxHeight=\"-Infinity\" maxWidth=\"-Infinity\">"));
        assertTrue(fxml.contains("fx:id=\"aboutButton\""));
        assertTrue(fxml.contains("fx:id=\"aboutUpdateBadge\""));
    }

    @Test
    void stylesheetDefinesModalOverlayUpdateAlertBannerAndClearsFocusRing() throws Exception {
        String css;
        try (var stream = getClass().getResourceAsStream("/css/styles.css")) {
            assertNotNull(stream);
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(css.contains(".modal-overlay"));
        assertTrue(css.contains(".modal-card"));
        assertTrue(css.contains(".modal-scroll:focused"));
        assertTrue(css.contains(".scroll-pane:focused"));
        assertTrue(css.contains(".update-badge-dot"));
        assertTrue(css.contains(".update-alert-box"));
        assertTrue(css.contains(".update-alert-title"));
        assertTrue(css.contains(".update-alert-body"));
    }

    @Test
    void aboutCardKeepsLatestReleaseLinksPermanentAndResetsUpdateBannerAcrossReopen() throws Exception {
        UpdateCheckClient.ReleaseInfo release = new UpdateCheckClient.ReleaseInfo(
                AppVersion.parse("0.8.0").orElseThrow(),
                "v0.8.0",
                "0.8.0",
                "GPU 顶点着色优化",
                "https://gitee.com/huang3337/MC-World-Explorer/releases/tag/v0.8.0",
                "https://github.com/huang3337/MC-World-Explorer/releases/tag/v0.8.0",
                UpdateCheckClient.Source.GITEE);

        AtomicReference<String> confirmedRepoUrl = new AtomicReference<>();
        CountDownLatch checkDone = new CountDownLatch(1);

        VBox firstCard = onFxThread(() -> {
            VBox card = AboutDialog.createAboutCard(
                    url -> { },
                    ignored -> { },
                    () -> UpdateCheckClient.CheckResult.updateAvailable(release),
                    (label, url) -> confirmedRepoUrl.set(url),
                    () -> { });
            new javafx.scene.Scene(card, 600, 700);
            card.applyCss();
            card.layout();

            Hyperlink giteeLink = (Hyperlink) card.lookup("#aboutGiteeReleaseLink");
            Hyperlink githubLink = (Hyperlink) card.lookup("#aboutGithubReleaseLink");
            Hyperlink bilibiliLink = (Hyperlink) card.lookup("#aboutBilibiliLink");
            VBox updateBox = (VBox) card.lookup("#aboutUpdateBox");

            assertNotNull(giteeLink);
            assertNotNull(githubLink);
            assertNotNull(bilibiliLink);
            assertEquals(AllowedExternalUrls.GITEE_LATEST_RELEASE_URL, giteeLink.getText());
            assertEquals(AllowedExternalUrls.GITHUB_LATEST_RELEASE_URL, githubLink.getText());
            assertTrue(giteeLink.isVisible() && giteeLink.isManaged());
            assertTrue(githubLink.isVisible() && githubLink.isManaged());
            assertFalse(updateBox.isVisible());
            assertFalse(updateBox.isManaged());

            // Clicking permanent link before checking update targets /releases/latest via confirmation callback.
            giteeLink.fire();
            assertEquals(AllowedExternalUrls.GITEE_LATEST_RELEASE_URL, confirmedRepoUrl.get());
            githubLink.fire();
            assertEquals(AllowedExternalUrls.GITHUB_LATEST_RELEASE_URL, confirmedRepoUrl.get());

            // Trigger manual update check.
            Button checkBtn = (Button) card.lookup("#aboutCheckUpdateButton");
            checkBtn.fire();
            return card;
        });

        // Wait for background worker + Platform.runLater renderUpdate to complete.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean visible = onFxThread(() -> firstCard.lookup("#aboutUpdateBox").isVisible());
            if (Boolean.TRUE.equals(visible)) {
                checkDone.countDown();
                break;
            }
            Thread.sleep(25);
        }
        assertEquals(0L, checkDone.getCount(), "Expected prominent update banner to appear after checking update");

        VBox secondCard = onFxThread(() -> {
            VBox updateBox = (VBox) firstCard.lookup("#aboutUpdateBox");
            Label title = (Label) firstCard.lookup("#aboutUpdateTitle");
            Label guide = (Label) firstCard.lookup("#aboutUpdateGuide");
            assertTrue(updateBox.getStyleClass().contains("update-alert-box"));
            assertTrue(title.getText().contains("v0.8.0"));
            assertTrue(guide.getText().contains(".zip"));

            // Simulate closing and reopening About modal: new card starts with banner hidden, permanent links intact.
            VBox reopenedCard = AboutDialog.createAboutCard(
                    url -> { },
                    ignored -> { },
                    () -> UpdateCheckClient.CheckResult.updateAvailable(release),
                    (label, url) -> confirmedRepoUrl.set(url),
                    () -> { });
            new javafx.scene.Scene(reopenedCard, 600, 700);
            reopenedCard.applyCss();
            reopenedCard.layout();
            assertFalse(reopenedCard.lookup("#aboutUpdateBox").isVisible());
            assertFalse(reopenedCard.lookup("#aboutUpdateBox").isManaged());
            assertTrue(reopenedCard.lookup("#aboutGiteeReleaseLink").isVisible());
            assertTrue(reopenedCard.lookup("#aboutGithubReleaseLink").isVisible());
            ((Button) reopenedCard.lookup("#aboutCheckUpdateButton")).fire();
            return reopenedCard;
        });

        CountDownLatch recheckDone = new CountDownLatch(1);
        long secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < secondDeadline) {
            Boolean visible = onFxThread(() -> secondCard.lookup("#aboutUpdateBox").isVisible());
            if (Boolean.TRUE.equals(visible)) {
                recheckDone.countDown();
                break;
            }
            Thread.sleep(25);
        }
        assertEquals(0L, recheckDone.getCount(), "Expected update banner to reappear when checking update again");
    }

    @Test
    void windowsPackagerIncludesEcCryptoModuleAndCurrentVersion() throws Exception {
        Path script = Path.of("tools/package-windows.ps1");
        assertTrue(Files.isRegularFile(script));
        String content = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(content.contains("java.se,jdk.unsupported,jdk.crypto.ec"));
        assertTrue(content.contains("-Djava.net.useSystemProxies=true"));
        assertTrue(content.contains("core-0.8.0"));
    }

    @Test
    void headerAboutButtonWrapperAlignsVerticallyWithSiblingToolbarButtons() throws Exception {
        onFxThread(() -> {
            javafx.scene.layout.HBox header = new javafx.scene.layout.HBox(12.0);
            header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            header.getStyleClass().add("world-header");

            javafx.scene.layout.VBox titleBox = new javafx.scene.layout.VBox(3.0,
                    new Label("选择一个世界"), new Label("无需启动 Minecraft，即可浏览已有世界"));
            javafx.scene.layout.HBox.setHgrow(titleBox, javafx.scene.layout.Priority.ALWAYS);

            Button settingsButton = new Button("设置");
            settingsButton.getStyleClass().add("quiet-button");

            Button aboutButton = new Button("关于");
            aboutButton.getStyleClass().add("quiet-button");
            javafx.scene.layout.Region badge = new javafx.scene.layout.Region();
            badge.getStyleClass().add("update-badge-dot");
            badge.setVisible(true);
            badge.setManaged(true);
            javafx.scene.layout.StackPane.setAlignment(badge, javafx.geometry.Pos.TOP_RIGHT);

            javafx.scene.layout.StackPane aboutWrapper = new javafx.scene.layout.StackPane(aboutButton, badge);
            aboutWrapper.setAlignment(javafx.geometry.Pos.CENTER);
            aboutWrapper.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            aboutWrapper.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

            header.getChildren().addAll(titleBox, settingsButton, aboutWrapper);
            javafx.scene.Scene scene = new javafx.scene.Scene(header, 800, 80);
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            header.applyCss();
            header.layout();

            double settingsY = settingsButton.localToScene(settingsButton.getBoundsInLocal()).getMinY();
            double aboutY = aboutButton.localToScene(aboutButton.getBoundsInLocal()).getMinY();
            assertEquals(settingsY, aboutY, 0.5, "About button must vertically align with Settings button");
            assertEquals(settingsButton.getHeight(), aboutButton.getHeight(), 0.5);
            return null;
        });
    }

    @Test
    void loadingMainFxmlIntoSceneProducesZeroCssLookupWarnings() throws Exception {
        java.util.List<String> cssWarnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger cssLogger = java.util.logging.Logger.getLogger("javafx.scene.CssStyleHelper");
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record != null && record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    cssWarnings.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        cssLogger.addHandler(handler);
        String prevSkip = System.getProperty("mcwe.skipFirstRunImport");
        System.setProperty("mcwe.skipFirstRunImport", "true");
        try {
            onFxThread(() -> {
                javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                        getClass().getResource("/fxml/main.fxml"),
                        java.util.ResourceBundle.getBundle("messages"));
                javafx.scene.Parent root = loader.load();
                MainController controller = loader.getController();
                try {
                    javafx.scene.Scene scene = new javafx.scene.Scene(root, 1100, 720);
                    assertFalse(scene.getStylesheets().isEmpty(), "Stylesheets must be registered before first CSS pass");
                    root.applyCss();
                    root.layout();
                } finally {
                    controller.shutdown();
                }
                return null;
            });
            assertTrue(cssWarnings.isEmpty(), () -> "Unexpected JavaFX CSS warnings: " + cssWarnings);
        } finally {
            cssLogger.removeHandler(handler);
            if (prevSkip == null) System.clearProperty("mcwe.skipFirstRunImport");
            else System.setProperty("mcwe.skipFirstRunImport", prevSkip);
        }
    }
}
