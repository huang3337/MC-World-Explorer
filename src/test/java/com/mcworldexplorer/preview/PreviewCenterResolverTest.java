package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewCenterResolverTest {
    @Test
    void prefersOverworldPlayerRespawn() {
        WorldInfo info = worldWithSpawn();
        info.setPlayerRespawnPosition(-20, 70, 45, "minecraft:overworld");

        assertEquals(
                new PreviewCenter(-20, 45, PreviewCenterSource.PLAYER_RESPAWN),
                PreviewCenterResolver.resolve(info));
    }

    @Test
    void treatsMissingAndLegacyZeroDimensionAsOverworld() {
        WorldInfo missingDimension = worldWithSpawn();
        missingDimension.setPlayerRespawnPosition(1, 64, 2, null);
        WorldInfo legacyDimension = worldWithSpawn();
        legacyDimension.setPlayerRespawnPosition(3, 64, 4, "0");

        assertEquals(PreviewCenterSource.PLAYER_RESPAWN,
                PreviewCenterResolver.resolve(missingDimension).source());
        assertEquals(PreviewCenterSource.PLAYER_RESPAWN,
                PreviewCenterResolver.resolve(legacyDimension).source());
    }

    @Test
    void rejectsNetherEndAndUnknownRespawnPoints() {
        WorldInfo nether = worldWithSpawn();
        nether.setPlayerRespawnPosition(1, 64, 2, "minecraft:the_nether");
        WorldInfo end = worldWithSpawn();
        end.setPlayerRespawnPosition(3, 64, 4, "minecraft:the_end");
        WorldInfo unknown = worldWithSpawn();
        unknown.setPlayerRespawnPosition(5, 64, 6, "");
        WorldInfo legacyNether = worldWithSpawn();
        legacyNether.setPlayerRespawnPosition(7, 64, 8, "-1");

        assertEquals(PreviewCenterSource.WORLD_SPAWN, PreviewCenterResolver.resolve(nether).source());
        assertEquals(PreviewCenterSource.WORLD_SPAWN, PreviewCenterResolver.resolve(end).source());
        assertEquals(PreviewCenterSource.WORLD_SPAWN, PreviewCenterResolver.resolve(unknown).source());
        assertEquals(PreviewCenterSource.WORLD_SPAWN, PreviewCenterResolver.resolve(legacyNether).source());
    }

    @Test
    void usesWorldSpawnWhenPlayerRespawnIsUnavailable() {
        assertEquals(
                new PreviewCenter(100, -200, PreviewCenterSource.WORLD_SPAWN),
                PreviewCenterResolver.resolve(worldWithSpawn()));
    }

    @Test
    void fallsBackToOriginWhenNoUsableSpawnIsAvailable() {
        WorldInfo info = new WorldInfo(Path.of("world"));
        info.setPlayerRespawnPosition(1, 64, 2, "minecraft:the_nether");

        assertEquals(
                new PreviewCenter(0, 0, PreviewCenterSource.ORIGIN_FALLBACK),
                PreviewCenterResolver.resolve(info));
    }

    @Test
    void rejectsNullWorldInfo() {
        assertThrows(NullPointerException.class, () -> PreviewCenterResolver.resolve(null));
    }

    private static WorldInfo worldWithSpawn() {
        WorldInfo info = new WorldInfo(Path.of("world"));
        info.setSpawnPosition(100, 64, -200);
        return info;
    }
}
