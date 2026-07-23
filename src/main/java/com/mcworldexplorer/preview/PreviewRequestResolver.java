package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;

import java.util.Objects;

public final class PreviewRequestResolver {
    private static final int NETHER_DEFAULT_Y = 64;

    private PreviewRequestResolver() {
    }

    public static PreviewRequest resolve(
            WorldInfo world,
            WorldDimension dimension,
            DimensionHeightRange heightRange,
            PreviewLayer requestedLayer) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(heightRange, "heightRange");
        if (requestedLayer != null && !heightRange.contains(requestedLayer)) {
            throw new IllegalArgumentException("requested layer is outside the dimension height range");
        }

        boolean playerInDimension = playerPositionMatches(world, dimension);
        PreviewCenter center = resolveCenter(world, dimension, playerInDimension);
        PreviewLayer layer = requestedLayer != null
                ? requestedLayer
                : resolveDefaultLayer(world, dimension, heightRange, playerInDimension);
        return new PreviewRequest(dimension, center, layer);
    }

    private static PreviewCenter resolveCenter(
            WorldInfo world,
            WorldDimension dimension,
            boolean playerInDimension) {
        if (dimension.isOverworld()) {
            return PreviewCenterResolver.resolve(world);
        }
        if (playerInDimension) {
            return new PreviewCenter(
                    floorToInt(world.getPlayerX()),
                    floorToInt(world.getPlayerZ()),
                    PreviewCenterSource.PLAYER_POSITION);
        }
        return new PreviewCenter(0, 0, PreviewCenterSource.DIMENSION_ORIGIN);
    }

    private static PreviewLayer resolveDefaultLayer(
            WorldInfo world,
            WorldDimension dimension,
            DimensionHeightRange heightRange,
            boolean playerInDimension) {
        if (playerInDimension) {
            return heightRange.bandContaining(floorToInt(world.getPlayerY()));
        }
        if (dimension.kind() == DimensionKind.NETHER) {
            return heightRange.bandContaining(NETHER_DEFAULT_Y);
        }
        return PreviewLayer.surfaceOverview();
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    public static boolean playerPositionMatches(WorldInfo world, WorldDimension dimension) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimension, "dimension");
        String playerDimension = world.getPlayerDimension();
        return playerDimension != null
                && !playerDimension.isBlank()
                && hasUsablePlayerPosition(world)
                && dimension.id().equals(WorldDimension.normalizeId(playerDimension));
    }

    private static boolean hasUsablePlayerPosition(WorldInfo world) {
        return world.isPlayerPositionAvailable()
                && Double.isFinite(world.getPlayerX())
                && Double.isFinite(world.getPlayerY())
                && Double.isFinite(world.getPlayerZ())
                && world.getPlayerX() >= Integer.MIN_VALUE
                && world.getPlayerX() <= Integer.MAX_VALUE
                && world.getPlayerY() >= Integer.MIN_VALUE
                && world.getPlayerY() <= Integer.MAX_VALUE
                && world.getPlayerZ() >= Integer.MIN_VALUE
                && world.getPlayerZ() <= Integer.MAX_VALUE;
    }
}
