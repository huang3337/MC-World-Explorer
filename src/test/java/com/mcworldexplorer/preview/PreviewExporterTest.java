package com.mcworldexplorer.preview;

import com.mcworldexplorer.preview.PreviewExporter.ExportException;
import com.mcworldexplorer.preview.PreviewExporter.FailureReason;
import com.mcworldexplorer.storage.PortablePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewExporterTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T07:30:45Z"),
            ZoneId.of("Asia/Shanghai"));

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(HOME_PROPERTY);

    @AfterEach
    void restoreApplicationHome() {
        if (originalHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void exportsExactBytesToPortableDirectoryWithSafeName() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path source = Files.write(tempDir.resolve("preview.png"), new byte[]{1, 2, 3, 4, 5});
        System.setProperty(HOME_PROPERTY, applicationRoot.toString());

        Path exported = new PreviewExporter(FIXED_CLOCK).exportToDefault(
                source,
                "A<World>. ",
                worldDirectory);

        assertEquals(applicationRoot.resolve("exports/A_World_-20260718-153045.png"), exported);
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(exported));
        assertTrue(Files.isDirectory(applicationRoot.resolve("exports")));
        try (Stream<Path> exportedFiles = Files.list(applicationRoot.resolve("exports"))) {
            assertFalse(exportedFiles
                    .anyMatch(path -> path.getFileName().toString().startsWith(".mcwe-export-")));
        }
    }

    @Test
    void appendsSequenceWithoutOverwritingExistingExports() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path source = Files.write(tempDir.resolve("preview.png"), new byte[]{9, 8, 7});
        System.setProperty(HOME_PROPERTY, applicationRoot.toString());
        PreviewExporter exporter = new PreviewExporter(FIXED_CLOCK);

        Path first = exporter.exportToDefault(source, "World", worldDirectory);
        Path second = exporter.exportToDefault(source, "World", worldDirectory);
        Path third = exporter.exportToDefault(source, "World", worldDirectory);

        assertEquals("World-20260718-153045.png", first.getFileName().toString());
        assertEquals("World-20260718-153045-2.png", second.getFileName().toString());
        assertEquals("World-20260718-153045-3.png", third.getFileName().toString());
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(first));
    }

    @Test
    void createsBoundedFallbackNamesForInvalidAndLongWorldNames() {
        PreviewExporter exporter = new PreviewExporter(FIXED_CLOCK);

        assertEquals("world-20260718-153045.png", exporter.suggestedFileName(".."));
        assertEquals("world-20260718-153045.png", exporter.suggestedFileName("   "));
        assertTrue(exporter.suggestedFileName("世界".repeat(100)).length() <= 104);
        assertTrue(exporter.suggestedFileName("世界".repeat(100)).endsWith("-20260718-153045.png"));
    }

    @Test
    void rejectsExistingAlternateTargetWithoutChangingIt() throws IOException {
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path source = Files.write(tempDir.resolve("preview.png"), new byte[]{1, 2, 3});
        Path target = Files.write(tempDir.resolve("existing.png"), new byte[]{7, 7});

        ExportException failure = assertThrows(
                ExportException.class,
                () -> new PreviewExporter(FIXED_CLOCK).exportToFile(source, target, worldDirectory));

        assertEquals(FailureReason.TARGET_EXISTS, failure.reason());
        assertArrayEquals(new byte[]{7, 7}, Files.readAllBytes(target));
    }

    @Test
    void rejectsTargetsInsideWorldDirectory() throws IOException {
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path nestedDirectory = Files.createDirectories(worldDirectory.resolve("screenshots/nested"));
        Path source = Files.write(tempDir.resolve("preview.png"), new byte[]{1, 2, 3});

        ExportException failure = assertThrows(
                ExportException.class,
                () -> new PreviewExporter(FIXED_CLOCK).exportToFile(
                        source,
                        nestedDirectory.resolve("preview.png"),
                        worldDirectory));

        assertEquals(FailureReason.TARGET_INSIDE_WORLD, failure.reason());
        assertTrue(Files.notExists(nestedDirectory.resolve("preview.png")));
    }

    @Test
    void reportsMissingSourceWithoutCreatingTarget() throws IOException {
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path target = tempDir.resolve("export.png");

        ExportException failure = assertThrows(
                ExportException.class,
                () -> new PreviewExporter(FIXED_CLOCK).exportToFile(
                        tempDir.resolve("missing.png"),
                        target,
                        worldDirectory));

        assertEquals(FailureReason.SOURCE_UNAVAILABLE, failure.reason());
        assertTrue(Files.notExists(target));
    }

    @Test
    void reportsUnavailablePortableExportDirectoryWithoutFallback() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Files.writeString(applicationRoot.resolve("exports"), "occupied");
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path source = Files.write(tempDir.resolve("preview.png"), new byte[]{1});
        System.setProperty(HOME_PROPERTY, applicationRoot.toString());

        ExportException failure = assertThrows(
                ExportException.class,
                () -> new PreviewExporter(FIXED_CLOCK).exportToDefault(
                        source,
                        "World",
                        worldDirectory));

        assertEquals(FailureReason.TARGET_DIRECTORY_UNAVAILABLE, failure.reason());
        assertTrue(Files.notExists(tempDir.resolve("exports")));
    }

    @Test
    void refusesDefaultExportWhenApplicationRootIsInsideWorld() throws IOException {
        Path worldDirectory = Files.createDirectory(tempDir.resolve("world"));
        Path applicationRoot = Files.createDirectory(worldDirectory.resolve("MC World Explorer"));
        Path source = Files.write(tempDir.resolve("preview.png"), new byte[]{1, 2});
        System.setProperty(HOME_PROPERTY, applicationRoot.toString());

        ExportException failure = assertThrows(
                ExportException.class,
                () -> new PreviewExporter(FIXED_CLOCK).exportToDefault(
                        source,
                        "World",
                        worldDirectory));

        assertEquals(FailureReason.TARGET_INSIDE_WORLD, failure.reason());
        assertTrue(Files.notExists(applicationRoot.resolve("exports/World-20260718-153045.png")));
    }
}
