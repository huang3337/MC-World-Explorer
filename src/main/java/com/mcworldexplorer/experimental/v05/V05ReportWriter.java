package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.mesh.MeshWarning;
import com.mcworldexplorer.voxel.mesh.MeshBatch;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

public final class V05ReportWriter {
    public void write(Path target, V05ValidationReport report) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("report path has no parent: " + normalized);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, toJson(report), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    String toJson(V05ValidationReport report) {
        V05ValidationContext context = report.context();
        VoxelSceneSnapshot scene = context.sceneResult().snapshot().orElseThrow();
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n");
        field(json, "stage", "V0.5 Internal Validation", true);
        field(json, "world", context.arguments().world().toString(), true);
        field(json, "dimensionId", context.arguments().dimensionId(), true);
        number(json, "minChunkX", context.arguments().target().minX(), true);
        number(json, "minChunkZ", context.arguments().target().minZ(), true);
        number(json, "maxChunkX", context.arguments().target().maxX(), true);
        number(json, "maxChunkZ", context.arguments().target().maxZ(), true);
        number(json, "meshNanos", context.sceneResult().meshNanos(), true);
        number(json, "uploadNanos", report.renderMetrics().uploadNanos(), true);
        number(json, "firstFrameNanos", report.renderMetrics().firstFrameNanos(), true);
        number(json, "frameCount", report.renderMetrics().frameCount(), true);
        decimal(json, "averageFps", report.renderMetrics().averageFps(), true);
        number(json, "blockCount", scene.blockCount(), true);
        number(json, "faceCount", scene.faceCount(), true);
        number(json, "vertexCount", scene.vertexCount(), true);
        number(json, "indexCount", scene.indexCount(), true);
        number(json, "heapUsedBeforePrepare", report.memoryMetrics().heapUsedBeforePrepare(), true);
        number(json, "heapUsedAfterPrepare", report.memoryMetrics().heapUsedAfterPrepare(), true);
        number(json, "heapUsedAfterRender", report.memoryMetrics().heapUsedAfterRender(), true);
        number(json, "heapCommittedAfterRender", report.memoryMetrics().heapCommittedAfterRender(), true);
        number(json, "maxHeap", report.memoryMetrics().maxHeap(), true);
        field(json, "openGlVendor", report.renderMetrics().openGlVendor(), true);
        field(json, "openGlRenderer", report.renderMetrics().openGlRenderer(), true);
        field(json, "openGlVersion", report.renderMetrics().openGlVersion(), true);
        field(json, "javaVersion", System.getProperty("java.version", "unknown"), true);
        field(json, "os", System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.arch", "unknown"), true);
        json.append("  \"cancelled\": ").append(context.sceneResult().cancelled()).append(",\n");
        json.append("  \"resourcesReleased\": ").append(report.resourcesReleased()).append(",\n");
        appendLayers(json, scene);
        json.append("  \"chunks\": [\n");
        int chunkIndex = 0;
        for (ChunkLoadResult result : context.loadResult().results().values()) {
            if (chunkIndex++ > 0) {
                json.append(",\n");
            }
            json.append("    {\"x\": ").append(result.coordinate().x())
                    .append(", \"z\": ").append(result.coordinate().z())
                    .append(", \"status\": \"").append(result.status()).append("\"")
                    .append(", \"regionReadNanos\": ").append(result.regionReadNanos())
                    .append(", \"parseNanos\": ").append(result.parseNanos())
                    .append(", \"detail\": \"").append(escape(result.detail())).append("\"}");
        }
        json.append("\n  ],\n  \"warnings\": [\n");
        for (int index = 0; index < scene.warnings().size(); index++) {
            MeshWarning warning = scene.warnings().get(index);
            if (index > 0) {
                json.append(",\n");
            }
            json.append("    {\"code\": \"").append(warning.code())
                    .append("\", \"chunkX\": ").append(warning.coordinate().x())
                    .append(", \"chunkZ\": ").append(warning.coordinate().z())
                    .append(", \"detail\": \"").append(escape(warning.detail()))
                    .append("\", \"occurrences\": ").append(warning.occurrences()).append('}');
        }
        json.append("\n  ]\n}\n");
        return json.toString();
    }

    private static void appendLayers(StringBuilder json, VoxelSceneSnapshot scene) {
        Map<RenderLayer, LayerTotals> totals = new EnumMap<>(RenderLayer.class);
        for (var chunk : scene.chunks()) {
            for (MeshBatch batch : chunk.batches()) {
                totals.computeIfAbsent(batch.layer(), ignored -> new LayerTotals()).add(batch);
            }
        }
        json.append("  \"layers\": [\n");
        int index = 0;
        for (RenderLayer layer : RenderLayer.values()) {
            LayerTotals total = totals.get(layer);
            if (total == null) {
                continue;
            }
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("    {\"layer\": \"").append(layer)
                    .append("\", \"blockCount\": ").append(total.blocks)
                    .append(", \"faceCount\": ").append(total.faces)
                    .append(", \"vertexCount\": ").append(total.vertices)
                    .append(", \"indexCount\": ").append(total.indices).append('}');
        }
        json.append("\n  ],\n");
    }

    private static final class LayerTotals {
        private long blocks;
        private long faces;
        private long vertices;
        private long indices;

        private void add(MeshBatch batch) {
            blocks += batch.blockCount();
            faces += batch.faceCount();
            vertices += batch.vertexCount();
            indices += batch.indices().length;
        }
    }

    private static void field(StringBuilder json, String name, String value, boolean comma) {
        json.append("  \"").append(name).append("\": \"")
                .append(escape(value)).append('"').append(comma ? ",\n" : "\n");
    }

    private static void number(StringBuilder json, String name, long value, boolean comma) {
        json.append("  \"").append(name).append("\": ").append(value)
                .append(comma ? ",\n" : "\n");
    }

    private static void decimal(StringBuilder json, String name, double value, boolean comma) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON number must be finite: " + name);
        }
        json.append("  \"").append(name).append("\": ").append(value)
                .append(comma ? ",\n" : "\n");
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
