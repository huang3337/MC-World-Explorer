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
    void distinguishesAvailableFolderTimeAndZeroSpawnPosition() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));

        assertFalse(info.isFolderCreationTimeAvailable());
        assertFalse(info.isSpawnPositionAvailable());

        info.setFolderCreationTime(1_700_000_000_000L);
        info.setSpawnPosition(0, 0, 0);

        assertTrue(info.isFolderCreationTimeAvailable());
        assertEquals(1_700_000_000_000L, info.getFolderCreationTime());
        assertTrue(info.isSpawnPositionAvailable());
        assertEquals(0, info.getSpawnX());
        assertEquals(0, info.getSpawnY());
        assertEquals(0, info.getSpawnZ());
    }

    @Test
    void distinguishesMissingAndAvailablePlayerRespawnPosition() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));

        assertFalse(info.isPlayerRespawnPositionAvailable());

        info.setPlayerRespawnPosition(0, 64, -8, "minecraft:overworld");

        assertTrue(info.isPlayerRespawnPositionAvailable());
        assertEquals(0, info.getPlayerRespawnX());
        assertEquals(64, info.getPlayerRespawnY());
        assertEquals(-8, info.getPlayerRespawnZ());
        assertEquals("minecraft:overworld", info.getPlayerRespawnDimension());
    }

    @Test
    void toStringContainsDiagnosticFields() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));
        String value = info.toString();

        assertTrue(value.contains("folderPath="));
        assertTrue(value.contains("lastPlayed="));
        assertTrue(value.contains("folderCreationTime="));
        assertTrue(value.contains("gameTime="));
        assertTrue(value.contains("randomSeed="));
        assertTrue(value.contains("spawnPos="));
        assertTrue(value.contains("playerRespawnPos="));
        assertTrue(value.contains("playerPos="));
    }
}
