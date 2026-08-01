package com.mcworldexplorer.experimental.v04.render.javafx;

import com.mcworldexplorer.experimental.v04.render.OrbitCameraState;
import com.mcworldexplorer.experimental.v04.render.V04AutomatedMotion;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SubScene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.transform.Affine;

public final class JavaFxOrbitController {
    private static final double ROTATION_SENSITIVITY = 0.008;
    private static final double ZOOM_BASE = 1.0015;

    private final Group worldGroup;
    private final PerspectiveCamera camera;
    private final OrbitCameraState initialState;
    private final Affine viewTransform = new Affine();
    private OrbitCameraState state;
    private double lastX;
    private double lastY;

    public JavaFxOrbitController(
            SubScene scene,
            Group worldGroup,
            PerspectiveCamera camera,
            OrbitCameraState initialState) {
        this.worldGroup = worldGroup;
        this.camera = camera;
        this.initialState = initialState;
        this.state = initialState;
        worldGroup.getTransforms().setAll(viewTransform);
        scene.setFocusTraversable(true);
        scene.setOnMousePressed(event -> {
            scene.requestFocus();
            if (event.getButton() == MouseButton.PRIMARY) {
                lastX = event.getSceneX();
                lastY = event.getSceneY();
            }
        });
        scene.setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            double deltaX = event.getSceneX() - lastX;
            double deltaY = event.getSceneY() - lastY;
            lastX = event.getSceneX();
            lastY = event.getSceneY();
            state = state.rotate(
                    deltaX * ROTATION_SENSITIVITY,
                    -deltaY * ROTATION_SENSITIVITY);
            apply();
        });
        scene.setOnScroll(event -> {
            state = state.zoomBy(Math.pow(ZOOM_BASE, -event.getDeltaY()));
            apply();
        });
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.R) {
                state = state.reset();
                apply();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                scene.getScene().getWindow().hide();
            }
        });
        apply();
    }

    void applyAutomatedMotion(double elapsedSeconds) {
        state = V04AutomatedMotion.stateAt(initialState, elapsedSeconds);
        apply();
    }

    private void apply() {
        JavaFxCameraTransform.update(viewTransform, state);
        camera.setTranslateX(0);
        camera.setTranslateY(0);
        camera.setTranslateZ(0);
    }
}
