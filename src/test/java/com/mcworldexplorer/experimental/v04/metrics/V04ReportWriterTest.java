package com.mcworldexplorer.experimental.v04.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class V04ReportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesUtf8JsonWithEscapedWindowsPathAndWarnings() throws Exception {
        V04RunMetrics metrics = new V04RunMetrics(
                "javafx",
                Path.of("C:\\Minecraft\\测试世界"),
                "minecraft:overworld",
                -1,
                2,
                1,
                2,
                3,
                4,
                5,
                1,
                6,
                4,
                2,
                7,
                3,
                28,
                42,
                -16,
                -64,
                32,
                0,
                320,
                48,
                100,
                120,
                new FrameTimeRecorder.Summary(2, 16, 20, 60.0),
                false,
                List.of("WEST: damaged \"chunk\""));
        Path report = tempDir.resolve("nested").resolve("report.json");

        new V04ReportWriter().write(report, metrics);

        String json = Files.readString(report);
        assertTrue(json.contains("C:\\\\Minecraft\\\\测试世界"));
        assertTrue(json.contains("damaged \\\"chunk\\\""));
        assertTrue(json.contains("\"fluidBlockCount\": 2"));
        assertTrue(json.contains("\"fluidFaceCount\": 3"));
        assertTrue(json.contains("\"meshMinY\": -64.0"));
        assertTrue(json.contains("\"boundaryComplete\": false"));
    }
}
