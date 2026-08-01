package com.mcworldexplorer.experimental.v04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V04ArgumentsTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesRequiredArgumentsAndOptionalReport() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        Path report = tempDir.resolve("reports").resolve("trial.json");

        V04Arguments arguments = V04Arguments.parse(new String[]{
                "--world", world.toString(),
                "--dimension", "minecraft:overworld",
                "--chunk-x", "-33",
                "--chunk-z", "64",
                "--report", report.toString(),
                "--screenshot", tempDir.resolve("trial.png").toString(),
                "--auto-close-seconds", "5",
                "--motion-after-seconds", "2"
        });

        assertEquals(world.toRealPath(), arguments.world());
        assertEquals("minecraft:overworld", arguments.dimensionId());
        assertEquals(-33, arguments.chunkX());
        assertEquals(64, arguments.chunkZ());
        assertEquals(tempDir.toRealPath().resolve("reports").resolve("trial.json"),
                arguments.report().orElseThrow());
        assertEquals(tempDir.toRealPath().resolve("trial.png"),
                arguments.screenshot().orElseThrow());
        assertEquals(5, arguments.autoCloseSeconds());
        assertEquals(2, arguments.motionAfterSeconds());
    }

    @Test
    void rejectsMissingDuplicateUnknownAndInvalidArguments() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(new String[]{"--world", world.toString()}));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(new String[]{
                        "--world", world.toString(), "--world", world.toString(),
                        "--dimension", "0", "--chunk-x", "0", "--chunk-z", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(new String[]{
                        "--world", world.toString(), "--dimension", "0",
                        "--chunk-x", "not-an-int", "--chunk-z", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(new String[]{
                        "--world", world.toString(), "--dimension", "0",
                        "--chunk-x", Integer.toString(Integer.MAX_VALUE), "--chunk-z", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(new String[]{
                        "--world", world.toString(), "--dimension", "0",
                        "--chunk-x", "0", "--chunk-z", "0", "--other", "x"}));
    }

    @Test
    void normalizesVanillaDimensionAliasesAndAllowsNoReport() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));

        V04Arguments arguments = V04Arguments.parse(new String[]{
                "--world", world.toString(),
                "--dimension", "-1",
                "--chunk-x", "0",
                "--chunk-z", "0"
        });

        assertEquals("minecraft:the_nether", arguments.dimensionId());
        assertFalse(arguments.report().isPresent());
        assertFalse(arguments.screenshot().isPresent());
        assertEquals(0, arguments.autoCloseSeconds());
        assertEquals(-1, arguments.motionAfterSeconds());
    }

    @Test
    void rejectsReportInsideWorldDirectory() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));

        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(new String[]{
                        "--world", world.toString(), "--dimension", "0",
                        "--chunk-x", "0", "--chunk-z", "0",
                        "--report", world.resolve("trial.json").toString()}));
    }

    @Test
    void rejectsInvalidAutoCloseAndScreenshotInsideWorld() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        String[] prefix = {"--world", world.toString(), "--dimension", "0",
                "--chunk-x", "0", "--chunk-z", "0"};

        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(concat(prefix, "--auto-close-seconds", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(concat(prefix, "--auto-close-seconds", "3601")));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(concat(prefix, "--screenshot", world.resolve("trial.png").toString())));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(concat(prefix, "--motion-after-seconds", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> V04Arguments.parse(concat(prefix,
                        "--auto-close-seconds", "5", "--motion-after-seconds", "5")));
    }

    private static String[] concat(String[] prefix, String... suffix) {
        String[] result = new String[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }
}
