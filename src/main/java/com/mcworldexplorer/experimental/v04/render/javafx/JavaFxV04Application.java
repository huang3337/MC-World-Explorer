package com.mcworldexplorer.experimental.v04.render.javafx;

import com.mcworldexplorer.experimental.v04.V04Arguments;
import com.mcworldexplorer.experimental.v04.V04ScreenshotWriter;
import com.mcworldexplorer.experimental.v04.V04TrialContext;
import com.mcworldexplorer.experimental.v04.V04TrialPipeline;
import com.mcworldexplorer.experimental.v04.metrics.V04ReportWriter;
import com.mcworldexplorer.experimental.v04.metrics.V04RunMetrics;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.AmbientLight;
import javafx.scene.DirectionalLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import java.io.IOException;

public final class JavaFxV04Application extends Application {
    private static long processStartNanos;

    private V04TrialContext context;
    private AnimationTimer timer;
    private boolean screenshotRequested;

    static void setProcessStartNanos(long value) {
        processStartNanos = value;
    }

    @Override
    public void start(Stage stage) throws Exception {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D)) {
            throw new IllegalStateException("JavaFX SCENE3D is not supported on this system");
        }
        V04Arguments arguments = V04Arguments.parse(
                getParameters().getRaw().toArray(String[]::new));
        context = new V04TrialPipeline().prepare(arguments, "javafx", processStartNanos);

        long uploadStart = System.nanoTime();
        Group meshGroup = new Group();
        JavaFxMeshAdapter adapter = new JavaFxMeshAdapter();
        context.snapshot().batches().forEach(batch -> meshGroup.getChildren().add(adapter.createView(batch)));

        AmbientLight ambient = new AmbientLight(Color.color(0.55, 0.55, 0.55));
        DirectionalLight directional = new DirectionalLight(Color.color(0.85, 0.85, 0.85));
        directional.setDirection(new javafx.geometry.Point3D(-1, -1, -1));
        Group root3d = new Group(meshGroup, ambient, directional);

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(Math.max(10_000, context.camera().distance() * 20));
        camera.setFieldOfView(45);
        camera.setVerticalFieldOfView(true);
        SubScene subScene = new SubScene(
                root3d,
                1280,
                800,
                true,
                SceneAntialiasing.BALANCED);
        subScene.setFill(Color.rgb(30, 34, 38));
        subScene.setCamera(camera);
        context.metrics().recordBackendUpload(System.nanoTime() - uploadStart);

        StackPane root = new StackPane(subScene);
        subScene.widthProperty().bind(root.widthProperty());
        subScene.heightProperty().bind(root.heightProperty());
        Scene scene = new Scene(root, 1280, 800);
        stage.setTitle("MC World Explorer V0.4 JavaFX 3D Trial");
        stage.setScene(scene);
        JavaFxOrbitController controller = new JavaFxOrbitController(
                subScene, meshGroup, camera, context.camera());
        stage.show();
        subScene.requestFocus();
        startFrameTimer(stage, subScene, controller);
    }

    private void startFrameTimer(
            Stage stage,
            SubScene subScene,
            JavaFxOrbitController controller) {
        long renderStart = System.nanoTime();
        long autoCloseAt = context.arguments().autoCloseSeconds() == 0
                ? Long.MAX_VALUE
                : System.nanoTime() + context.arguments().autoCloseSeconds() * 1_000_000_000L;
        timer = new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                context.metrics().markFirstFrame(System.nanoTime());
                if (previous != 0) {
                    context.metrics().frameTimes().record(now - previous);
                }
                previous = now;
                int motionAfter = context.arguments().motionAfterSeconds();
                double elapsedSeconds = (System.nanoTime() - renderStart) / 1_000_000_000.0;
                if (motionAfter >= 0 && elapsedSeconds >= motionAfter) {
                    controller.applyAutomatedMotion(elapsedSeconds - motionAfter);
                }
                if (!screenshotRequested && context.arguments().screenshot().isPresent()) {
                    screenshotRequested = true;
                    Platform.runLater(() -> captureScreenshot(subScene));
                }
                if (System.nanoTime() >= autoCloseAt) {
                    stage.close();
                }
            }
        };
        timer.start();
    }

    private void captureScreenshot(SubScene subScene) {
        int width = Math.max(1, (int) Math.round(subScene.getWidth()));
        int height = Math.max(1, (int) Math.round(subScene.getHeight()));
        WritableImage image = new WritableImage(width, height);
        subScene.snapshot(null, image);
        PixelReader reader = image.getPixelReader();
        int[] pixels = new int[Math.multiplyExact(width, height)];
        reader.getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        try {
            new V04ScreenshotWriter().write(
                    context.arguments().screenshot().orElseThrow(), width, height, pixels, false);
            System.out.println("V0.4 screenshot: " + context.arguments().screenshot().orElseThrow());
        } catch (IOException e) {
            System.err.println("Failed to write V0.4 screenshot: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (timer != null) {
            timer.stop();
        }
        if (context == null) {
            return;
        }
        V04RunMetrics metrics = context.metrics().snapshot();
        printSummary(metrics);
        context.arguments().report().ifPresent(path -> {
            try {
                new V04ReportWriter().write(path, metrics);
                System.out.println("V0.4 report: " + path);
            } catch (IOException e) {
                System.err.println("Failed to write V0.4 report: " + e.getMessage());
            }
        });
    }

    private static void printSummary(V04RunMetrics metrics) {
        System.out.println("V0.4 backend=" + metrics.backend()
                + " blocks=" + metrics.blockCount()
                + " faces=" + metrics.faceCount()
                + " vertices=" + metrics.vertexCount()
                + " firstFrameNanos=" + metrics.firstFrameNanos()
                + " averageFps=" + metrics.frameTimes().averageFps());
    }
}
