package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.*;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** Header-only overview. Choosing a location returns to the existing detailed viewport. */
final class SavedRangeOverview implements AutoCloseable {
    private final Dialog<Void> dialog = new Dialog<>();
    private final Canvas canvas = new Canvas(SavedRangeIndex.WIDTH, SavedRangeIndex.HEIGHT);
    private final Label status = new Label("正在读取 Region 头表，尚未解析地形…");
    private final AtomicReference<SavedRangeIndex.Snapshot> latest = new AtomicReference<>();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final MapTileScheduler scheduler;
    private final Supplier<Map<MapTileKey, Image>> terrain;
    private SavedRangeIndex.Snapshot shown;
    private WritableImage base;
    private volatile boolean closed;
    private final AnimationTimer pulse = new AnimationTimer() {
        private long last;
        @Override public void handle(long now) {
            var next = latest.getAndSet(null);
            if (next != null) {
                shown = next;
                base = new WritableImage(SavedRangeIndex.WIDTH, SavedRangeIndex.HEIGHT);
                base.getPixelWriter().setPixels(0, 0, SavedRangeIndex.WIDTH, SavedRangeIndex.HEIGHT,
                        PixelFormat.getIntArgbInstance(), next.pixels(), 0, SavedRangeIndex.WIDTH);
                String stage = next.limited() ? "索引达到预算上限，范围尚未完整" : next.complete() ? "头表索引结束（非地形解析完成）" : "正在读取头表";
                status.setText(stage + " · " + next.regions().size() + " 个 Region · " + next.chunks()
                        + " 个非空位置 · " + next.invalidChunks() + " 个损坏位置 · " + next.failures() + " 个读取失败"
                        + (next.bounds() == null ? "\n尚未发现已保存区块" : "")
                        + next.regions().stream().filter(r -> !r.failure().isEmpty()).findFirst()
                        .map(r -> "\n首个失败 r." + r.x() + "." + r.z() + ".mca：" + r.failure()).orElse(""));
            }
            String error = failure.getAndSet(null);
            if (error != null) status.setText("范围索引失败：" + error);
            if (next != null || now - last > 200_000_000L) { draw(); last = now; }
        }
    };
    SavedRangeOverview(Window owner, Path regionDirectory, MapTileScheduler scheduler,
                       Supplier<Map<MapTileKey, Image>> terrain, BiConsumer<Double, Double> locate) {
        this.scheduler = scheduler; this.terrain = terrain;
        dialog.setTitle("查看已保存范围"); dialog.initOwner(owner); dialog.setResizable(false);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        status.setWrapText(true); status.setMaxWidth(SavedRangeIndex.WIDTH);
        var legend = new Label("蓝色：头表占用，地形待解析　红色：读取异常\n叠加当前视口已加载地形，不代表重新解析；点击位置返回地图，按需补全细节。");
        legend.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(10, legend, canvas, status));
        canvas.setOnMouseClicked(event -> {
            if (shown == null || shown.bounds() == null) return;
            double x = shown.bounds().worldX(event.getX()), z = shown.bounds().worldZ(event.getY());
            if (Math.abs(x / 16) >= Integer.MAX_VALUE - 32 || Math.abs(z / 16) >= Integer.MAX_VALUE - 32) {
                status.setText("所选位置超出当前坐标支持范围"); return;
            }
            close(); locate.accept(x, z);
        });
        dialog.setOnHidden(event -> stop());
        dialog.show(); pulse.start();
        boolean accepted = scheduler.submitIndex(cancel -> SavedRangeIndex.scan(regionDirectory,
                () -> closed || cancel.isCancelled(), snapshot -> { if (!closed) latest.set(snapshot); }),
                error -> { if (!closed) failure.set(error.getClass().getSimpleName() + ": " + error.getMessage()); });
        if (!accepted) status.setText("后台队列已满，请稍后重新打开范围概览。");
    }
    private void draw() {
        var graphics = canvas.getGraphicsContext2D();
        graphics.setFill(javafx.scene.paint.Color.web("#20262b")); graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (base == null || shown == null) return;
        graphics.setImageSmoothing(false); graphics.drawImage(base, 0, 0);
        if (shown.bounds() == null) return;
        var bounds = shown.bounds();
        for (var entry : terrain.get().entrySet()) {
            var tile = entry.getKey().bounds();
            boolean damaged = shown.regions().stream().anyMatch(r -> (!r.failure().isEmpty() || r.hasInvalid())
                    && (long)r.x() * 512 < tile.maxXExclusive() && ((long)r.x() + 1) * 512 > tile.minX()
                    && (long)r.z() * 512 < tile.maxZExclusive() && ((long)r.z() + 1) * 512 > tile.minZ());
            if (damaged) continue; // Cached terrain must not conceal a current header failure.
            graphics.drawImage(entry.getValue(), bounds.screenX(tile.minX()), bounds.screenZ(tile.minZ()),
                    tile.blockWidth() / bounds.scale(), tile.blockHeight() / bounds.scale());
        }
    }
    private void stop() { if (closed) return; closed = true; pulse.stop(); scheduler.cancelIndex(); latest.set(null); shown = null; base = null; canvas.setWidth(1); canvas.setHeight(1); dialog.getDialogPane().setContent(null); }
    @Override public void close() { stop(); dialog.close(); }
}
