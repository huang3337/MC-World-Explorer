package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.PreviewExporter;
import com.mcworldexplorer.storage.PortablePaths;
import javafx.scene.control.Alert;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExportFolders {
    @FunctionalInterface
    interface Opener { void open(Path directory) throws IOException; }

    private final Opener opener;

    ExportFolders() { this(directory -> Desktop.getDesktop().open(directory.toFile())); }
    ExportFolders(Opener opener) { this.opener = opener; }

    void show(Window owner, Path directory, Path world) {
        try {
            if (directory.equals(PortablePaths.exportsDirectory()) && Files.notExists(directory)) {
                if (world != null) PreviewExporter.requireOutsideWorld(directory, world);
                Files.createDirectories(directory);
            }
            if (!Files.isDirectory(directory)) throw new IOException("文件夹不存在或不可访问");
            opener.open(directory);
        } catch (IOException | RuntimeException failure) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.initOwner(owner);
            alert.setTitle("打开文件夹");
            alert.setHeaderText("无法打开导出文件夹");
            alert.setContentText(directory + "\n" + failure.getMessage());
            alert.show();
        }
    }
}
