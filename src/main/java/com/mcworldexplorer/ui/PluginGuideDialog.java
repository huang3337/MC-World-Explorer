package com.mcworldexplorer.ui;

import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The same reviewed development guide is available offline in the portable application. */
final class PluginGuideDialog {
    private PluginGuideDialog() { }

    static void show(Window owner) {
        String text;
        try (var input=PluginGuideDialog.class.getResourceAsStream("/help/PLUGIN_DEVELOPMENT.md")) {
            if(input==null)throw new IOException("应用中缺少插件开发说明，请使用完整构建包。");
            byte[] bytes=input.readNBytes(256*1024+1);
            if(bytes.length>256*1024)throw new IOException("插件开发说明超过读取预算。");
            text=new String(bytes,StandardCharsets.UTF_8);
        } catch(IOException failure) {
            var alert=new Alert(Alert.AlertType.ERROR,failure.getMessage(),ButtonType.CLOSE);
            if(owner!=null)alert.initOwner(owner);
            alert.setHeaderText("无法打开开发说明");alert.show();return;
        }
        var dialog=new Dialog<Void>();if(owner!=null)dialog.initOwner(owner);
        dialog.setTitle("插件开发说明");dialog.setResizable(true);
        var content=new TextArea(text);content.setEditable(false);content.setWrapText(true);
        content.setPrefSize(760,550);content.setId("pluginDevelopmentGuide");
        content.setStyle("-fx-font-family: 'Consolas', 'Microsoft YaHei UI'; -fx-font-size: 12px;");
        dialog.getDialogPane().setContent(content);
        var copy=new ButtonType("复制全文",ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(copy,ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(copy).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();
            try {
                var data=new ClipboardContent();data.putString(content.getText());
                if(!Clipboard.getSystemClipboard().setContent(data))throw new IllegalStateException("剪贴板不可用");
                dialog.setHeaderText("已复制完整开发说明");
            } catch(RuntimeException failure) {
                dialog.setHeaderText("复制失败，可在下方文本中选择内容后重试。");
            }
        });
        dialog.show();
    }
}
