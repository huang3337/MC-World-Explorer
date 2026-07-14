package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;

import java.util.Objects;

public final class PreviewCenterResolver {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String LEGACY_OVERWORLD = "0";

    private PreviewCenterResolver() {
    }

    public static PreviewCenter resolve(WorldInfo worldInfo) {
        Objects.requireNonNull(worldInfo, "worldInfo");

        if (worldInfo.isPlayerRespawnPositionAvailable()
                && isOverworld(worldInfo.getPlayerRespawnDimension())) {
            return new PreviewCenter(
                    worldInfo.getPlayerRespawnX(),
                    worldInfo.getPlayerRespawnZ(),
                    PreviewCenterSource.PLAYER_RESPAWN);
        }

        if (worldInfo.isSpawnPositionAvailable()) {
            return new PreviewCenter(
                    worldInfo.getSpawnX(),
                    worldInfo.getSpawnZ(),
                    PreviewCenterSource.WORLD_SPAWN);
        }

        return new PreviewCenter(0, 0, PreviewCenterSource.ORIGIN_FALLBACK);
    }

    static boolean isOverworld(String dimension) {
        return dimension == null
                || OVERWORLD.equals(dimension)
                || LEGACY_OVERWORLD.equals(dimension);
    }
}
