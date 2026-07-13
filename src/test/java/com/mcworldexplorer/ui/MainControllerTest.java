package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainControllerTest {
    @Test
    void formatsMinecraftTicksAsReadableRealTime() {
        assertEquals("0m", MainController.formatGameTime(0));
        assertEquals("1m", MainController.formatGameTime(1_200));
        assertEquals("1h 1m", MainController.formatGameTime(73_200));
        assertEquals("1d 1h 1m", MainController.formatGameTime(1_801_200));
    }

    @Test
    void clampsNegativeGameTimeToZero() {
        assertEquals("0m", MainController.formatGameTime(-1));
    }

    @Test
    void countsWorldsAcrossDisplayedGroups() {
        WorldInfo first = new WorldInfo(Path.of("first"));
        WorldInfo second = new WorldInfo(Path.of("second"));

        assertEquals(2, MainController.countWorlds(Map.of(
                "Default", List.of(first),
                "Modpack", List.of(second))));
        assertEquals(0, MainController.countWorlds(Map.of()));
    }
}
