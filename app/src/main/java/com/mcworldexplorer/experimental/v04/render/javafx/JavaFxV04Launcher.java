package com.mcworldexplorer.experimental.v04.render.javafx;

import javafx.application.Application;

public final class JavaFxV04Launcher {
    private JavaFxV04Launcher() {
    }

    public static void main(String[] arguments) {
        JavaFxV04Application.setProcessStartNanos(System.nanoTime());
        Application.launch(JavaFxV04Application.class, arguments);
    }
}
