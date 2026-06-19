package com.mcworldexplorer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class App extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        URL fxmlLocation = getClass().getResource("/fxml/main.fxml");
        if (fxmlLocation == null) {
            throw new IllegalStateException("Cannot find /fxml/main.fxml");
        }
        Parent root = FXMLLoader.load(fxmlLocation);
        Scene scene = new Scene(root, 900, 600);
        stage.setScene(scene);
        stage.setTitle("MC World Explorer");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

