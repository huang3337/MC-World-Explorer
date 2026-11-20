package com.mcworldexplorer.voxel.data;

/** Fluid identity and amount available directly from a saved block state. */
public enum SavedFluid {
    NONE, WATER, LAVA;

    public static boolean embeddedWater(BlockState state) {
        return "true".equals(state.properties().get("waterlogged")) || switch (state.name()) {
            case "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass" -> true;
            default -> false;
        };
    }

    public static SavedFluid of(BlockState state) {
        return switch (state.name()) {
            case "minecraft:water", "minecraft:bubble_column" -> WATER;
            case "minecraft:lava" -> LAVA;
            default -> embeddedWater(state) ? WATER : NONE;
        };
    }

    public static float ownHeight(BlockState state) {
        if (of(state) == NONE) return 0;
        if(!state.name().equals("minecraft:water") && !state.name().equals("minecraft:lava"))return 8f/9;
        String level = state.properties().get("level");
        int amount = 8;
        if (level != null && level.length() == 1 && level.charAt(0) >= '1' && level.charAt(0) <= '7')
            amount -= level.charAt(0) - '0';
        return amount / 9f;
    }
}
