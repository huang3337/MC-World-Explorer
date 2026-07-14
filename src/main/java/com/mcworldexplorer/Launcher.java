package com.mcworldexplorer;

import com.mcworldexplorer.storage.PortablePaths;
import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.io.IOException;

public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            PortablePaths.initialize();
        } catch (IOException | RuntimeException e) {
            showStartupError(e);
            return;
        }
        App.main(args);
    }

    private static void showStartupError(Exception exception) {
        String message = "无法在程序目录中创建日志文件：\n"
                + exception.getMessage()
                + "\n\n请将 MC World Explorer 移动到普通可写目录后重试。";
        System.err.println(message);

        try {
            Platform.startup(() -> {
                try {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("MC World Explorer");
                    alert.setHeaderText("程序目录不可写");
                    alert.setContentText(message);
                    alert.showAndWait();
                } catch (RuntimeException dialogFailure) {
                    System.err.println("无法显示启动错误对话框：" + dialogFailure.getMessage());
                } finally {
                    Platform.exit();
                }
            });
        } catch (RuntimeException dialogFailure) {
            System.err.println("无法显示启动错误对话框：" + dialogFailure.getMessage());
        }
    }
}
