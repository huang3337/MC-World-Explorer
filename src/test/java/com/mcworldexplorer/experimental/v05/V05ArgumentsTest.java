package com.mcworldexplorer.experimental.v05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V05ArgumentsTest {
    @TempDir
    Path temporary;

    @Test
    void parsesDefaultCenteredAndExplicitRanges() throws Exception {
        Path world = Files.createDirectory(temporary.resolve("world"));
        V05Arguments centered = V05Arguments.parse(new String[]{
                "--world", world.toString(),
                "--dimension", "0",
                "--center-chunk-x", "10",
                "--center-chunk-z", "-4"
        });
        assertEquals("minecraft:overworld", centered.dimensionId());
        assertEquals(9, centered.target().minX());
        assertEquals(11, centered.target().maxX());
        assertEquals(3, centered.target().depth());

        V05Arguments explicit = V05Arguments.parse(new String[]{
                "--world", world.toString(),
                "--dimension", "minecraft:the_nether",
                "--min-chunk-x", "-2",
                "--min-chunk-z", "3",
                "--max-chunk-x", "1",
                "--max-chunk-z", "5"
        });
        assertEquals(4, explicit.target().width());
        assertEquals(3, explicit.target().depth());
    }

    @Test
    void rejectsMixedOversizedAndWorldInternalOutputs() throws Exception {
        Path world = Files.createDirectory(temporary.resolve("world"));
        assertThrows(IllegalArgumentException.class, () -> V05Arguments.parse(new String[]{
                "--world", world.toString(), "--dimension", "0",
                "--center-chunk-x", "0", "--center-chunk-z", "0",
                "--min-chunk-x", "0", "--min-chunk-z", "0",
                "--max-chunk-x", "0", "--max-chunk-z", "0"
        }));
        assertThrows(IllegalArgumentException.class, () -> V05Arguments.parse(new String[]{
                "--world", world.toString(), "--dimension", "0",
                "--center-chunk-x", "0", "--center-chunk-z", "0",
                "--width", "9"
        }));
        assertThrows(IllegalArgumentException.class, () -> V05Arguments.parse(new String[]{
                "--world", world.toString(), "--dimension", "0",
                "--center-chunk-x", "0", "--center-chunk-z", "0",
                "--report", world.resolve("report.json").toString()
        }));
    }
}
