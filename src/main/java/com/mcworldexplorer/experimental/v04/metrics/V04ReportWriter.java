package com.mcworldexplorer.experimental.v04.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class V04ReportWriter {
    public void write(Path target, V04RunMetrics metrics) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("report path has no parent: " + normalized);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, toJson(metrics), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    String toJson(V04RunMetrics metrics) {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\n");
        field(json, "backend", metrics.backend(), true);
        field(json, "world", metrics.world().toString(), true);
        field(json, "dimensionId", metrics.dimensionId(), true);
        number(json, "chunkX", metrics.chunkX(), true);
        number(json, "chunkZ", metrics.chunkZ(), true);
        number(json, "regionReadNanos", metrics.regionReadNanos(), true);
        number(json, "parseNanos", metrics.parseNanos(), true);
        number(json, "meshNanos", metrics.meshNanos(), true);
        number(json, "backendUploadNanos", metrics.backendUploadNanos(), true);
        number(json, "firstFrameNanos", metrics.firstFrameNanos(), true);
        number(json, "batchCount", metrics.batchCount(), true);
        number(json, "blockCount", metrics.blockCount(), true);
        number(json, "solidBlockCount", metrics.solidBlockCount(), true);
        number(json, "fluidBlockCount", metrics.fluidBlockCount(), true);
        number(json, "faceCount", metrics.faceCount(), true);
        number(json, "fluidFaceCount", metrics.fluidFaceCount(), true);
        number(json, "vertexCount", metrics.vertexCount(), true);
        number(json, "indexCount", metrics.indexCount(), true);
        decimal(json, "meshMinX", metrics.meshMinX(), true);
        decimal(json, "meshMinY", metrics.meshMinY(), true);
        decimal(json, "meshMinZ", metrics.meshMinZ(), true);
        decimal(json, "meshMaxX", metrics.meshMaxX(), true);
        decimal(json, "meshMaxY", metrics.meshMaxY(), true);
        decimal(json, "meshMaxZ", metrics.meshMaxZ(), true);
        number(json, "heapBeforeBytes", metrics.heapBeforeBytes(), true);
        number(json, "heapAfterBytes", metrics.heapAfterBytes(), true);
        number(json, "frameSampleCount", metrics.frameTimes().sampleCount(), true);
        number(json, "frameMedianNanos", metrics.frameTimes().medianNanos(), true);
        number(json, "frameP95Nanos", metrics.frameTimes().p95Nanos(), true);
        json.append("  \"averageFps\": ").append(metrics.frameTimes().averageFps()).append(",\n");
        json.append("  \"boundaryComplete\": ").append(metrics.boundaryComplete()).append(",\n");
        json.append("  \"warnings\": [");
        for (int index = 0; index < metrics.warnings().size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append('\"').append(escape(metrics.warnings().get(index))).append('\"');
        }
        json.append("]\n}\n");
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value, boolean comma) {
        json.append("  \"").append(name).append("\": \"")
                .append(escape(value)).append('\"');
        json.append(comma ? ",\n" : "\n");
    }

    private static void number(StringBuilder json, String name, long value, boolean comma) {
        json.append("  \"").append(name).append("\": ").append(value);
        json.append(comma ? ",\n" : "\n");
    }

    private static void decimal(StringBuilder json, String name, double value, boolean comma) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON number must be finite: " + name);
        }
        json.append("  \"").append(name).append("\": ").append(value);
        json.append(comma ? ",\n" : "\n");
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\"' -> escaped.append("\\\"");
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
