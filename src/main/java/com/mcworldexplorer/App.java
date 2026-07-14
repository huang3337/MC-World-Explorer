package com.mcworldexplorer;

import com.mcworldexplorer.storage.PortablePaths;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ResourceBundle;

public class App extends Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);
    private static final int DEFAULT_WIDTH = 1100;
    private static final int DEFAULT_HEIGHT = 720;

    @Override
    public void start(Stage stage) throws Exception {
        URL fxmlLocation = getClass().getResource("/fxml/main.fxml");
        if (fxmlLocation == null) {
            throw new IllegalStateException("Cannot find /fxml/main.fxml");
        }
        FXMLLoader loader = new FXMLLoader(fxmlLocation, ResourceBundle.getBundle("messages"));
        Parent root = loader.load();
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        URL stylesheet = getClass().getResource("/css/styles.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Cannot find /css/styles.css");
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        stage.setTitle("MC World Explorer");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable));
        LOGGER.info("Using application root {}", PortablePaths.applicationRoot());
        launch(args);
    }
}
