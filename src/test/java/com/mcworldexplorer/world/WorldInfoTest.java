package com.mcworldexplorer.world;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldInfoTest {
    @Test
    void keepsRequiredFieldsValid() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));

        info.setLevelName(null);
        info.setVersionName(null);
        info.setGameType(null);
        info.setLastPlayed(-1);

        assertEquals("world-folder", info.getLevelName());
        assertEquals("Unknown", info.getVersionName());
        assertEquals(GameType.SURVIVAL, info.getGameType());
        assertEquals(0, info.getLastPlayed());
    }

    @Test
    void distinguishesZeroSeedAndMissingPlayerPosition() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));

        assertFalse(info.isSeedAvailable());
        assertFalse(info.isPlayerPositionAvailable());

        info.setRandomSeed(0);
        info.setPlayerPosition(0, 0, 0);

        assertTrue(info.isSeedAvailable());
        assertTrue(info.isPlayerPositionAvailable());
        assertEquals(0, info.getRandomSeed());
    }

    @Test
    void toStringContainsDiagnosticFields() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));
        String value = info.toString();

        assertTrue(value.contains("folderPath="));
        assertTrue(value.contains("lastPlayed="));
        assertTrue(value.contains("randomSeed="));
        assertTrue(value.contains("playerPos="));
    }
}
