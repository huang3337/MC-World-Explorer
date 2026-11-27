package com.mcworldexplorer.voxel.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Standard vanilla numeric biome IDs (1.2–1.17.1) and baseline climate parameters
 * for pre-1.19.3 clients that do not bundle {@code data/minecraft/worldgen/biome/*.json}.
 */
public final class VanillaLegacyBiomes {
    public record Climate(
            float temperature,
            float downfall,
            int waterColor,
            String grassColorModifier,
            Integer grassColorOverride,
            Integer foliageColorOverride) {
    }

    private static final int DEFAULT_WATER = 0x3F76E4;
    private static final int SWAMP_WATER = 0x617B64;
    private static final int COLD_WATER = 0x3D57D6;
    private static final int FROZEN_WATER = 0x3938C9;
    private static final int LUKEWARM_WATER = 0x45ADF2;
    private static final int WARM_WATER = 0x43D5EE;
    private static final int BADLANDS_GRASS = 0x90814D;
    private static final int BADLANDS_FOLIAGE = 0x9E814D;
    private static final int SWAMP_FOLIAGE = 0x6A7039;

    private static final Map<Integer, String> ID_TO_NAME;
    private static final Map<String, Climate> NAME_TO_CLIMATE;

    static {
        Map<Integer, String> ids = new HashMap<>();
        Map<String, Climate> climates = new HashMap<>();

        register(ids, climates, 0, "minecraft:ocean", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 1, "minecraft:plains", 0.8f, 0.4f, DEFAULT_WATER);
        register(ids, climates, 2, "minecraft:desert", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 3, "minecraft:mountains", 0.2f, 0.3f, DEFAULT_WATER);
        register(ids, climates, 4, "minecraft:forest", 0.7f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 5, "minecraft:taiga", 0.25f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 6, "minecraft:swamp", 0.8f, 0.9f, SWAMP_WATER, "swamp", null, SWAMP_FOLIAGE);
        register(ids, climates, 7, "minecraft:river", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 8, "minecraft:nether_wastes", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 9, "minecraft:the_end", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 10, "minecraft:frozen_ocean", 0.0f, 0.5f, FROZEN_WATER);
        register(ids, climates, 11, "minecraft:frozen_river", 0.0f, 0.5f, FROZEN_WATER);
        register(ids, climates, 12, "minecraft:snowy_tundra", 0.0f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 13, "minecraft:snowy_mountains", 0.0f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 14, "minecraft:mushroom_fields", 0.9f, 1.0f, DEFAULT_WATER);
        register(ids, climates, 15, "minecraft:mushroom_field_shore", 0.9f, 1.0f, DEFAULT_WATER);
        register(ids, climates, 16, "minecraft:beach", 0.8f, 0.4f, DEFAULT_WATER);
        register(ids, climates, 17, "minecraft:desert_hills", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 18, "minecraft:wooded_hills", 0.7f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 19, "minecraft:taiga_hills", 0.25f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 20, "minecraft:mountain_edge", 0.2f, 0.3f, DEFAULT_WATER);
        register(ids, climates, 21, "minecraft:jungle", 0.95f, 0.9f, DEFAULT_WATER);
        register(ids, climates, 22, "minecraft:jungle_hills", 0.95f, 0.9f, DEFAULT_WATER);
        register(ids, climates, 23, "minecraft:jungle_edge", 0.95f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 24, "minecraft:deep_ocean", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 25, "minecraft:stone_shore", 0.2f, 0.3f, DEFAULT_WATER);
        register(ids, climates, 26, "minecraft:snowy_beach", 0.05f, 0.3f, COLD_WATER);
        register(ids, climates, 27, "minecraft:birch_forest", 0.6f, 0.6f, DEFAULT_WATER);
        register(ids, climates, 28, "minecraft:birch_forest_hills", 0.6f, 0.6f, DEFAULT_WATER);
        register(ids, climates, 29, "minecraft:dark_forest", 0.7f, 0.8f, DEFAULT_WATER, "dark_forest", null, null);
        register(ids, climates, 30, "minecraft:snowy_taiga", -0.5f, 0.4f, COLD_WATER);
        register(ids, climates, 31, "minecraft:snowy_taiga_hills", -0.5f, 0.4f, COLD_WATER);
        register(ids, climates, 32, "minecraft:giant_tree_taiga", 0.3f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 33, "minecraft:giant_tree_taiga_hills", 0.3f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 34, "minecraft:wooded_mountains", 0.2f, 0.3f, DEFAULT_WATER);
        register(ids, climates, 35, "minecraft:savanna", 1.2f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 36, "minecraft:savanna_plateau", 1.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 37, "minecraft:badlands", 2.0f, 0.0f, DEFAULT_WATER, "none", BADLANDS_GRASS, BADLANDS_FOLIAGE);
        register(ids, climates, 38, "minecraft:wooded_badlands_plateau", 2.0f, 0.0f, DEFAULT_WATER, "none", BADLANDS_GRASS, BADLANDS_FOLIAGE);
        register(ids, climates, 39, "minecraft:badlands_plateau", 2.0f, 0.0f, DEFAULT_WATER, "none", BADLANDS_GRASS, BADLANDS_FOLIAGE);
        register(ids, climates, 40, "minecraft:small_end_islands", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 41, "minecraft:end_midlands", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 42, "minecraft:end_highlands", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 43, "minecraft:end_barrens", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 44, "minecraft:warm_ocean", 0.5f, 0.5f, WARM_WATER);
        register(ids, climates, 45, "minecraft:lukewarm_ocean", 0.5f, 0.5f, LUKEWARM_WATER);
        register(ids, climates, 46, "minecraft:cold_ocean", 0.5f, 0.5f, COLD_WATER);
        register(ids, climates, 47, "minecraft:deep_warm_ocean", 0.5f, 0.5f, WARM_WATER);
        register(ids, climates, 48, "minecraft:deep_lukewarm_ocean", 0.5f, 0.5f, LUKEWARM_WATER);
        register(ids, climates, 49, "minecraft:deep_cold_ocean", 0.5f, 0.5f, COLD_WATER);
        register(ids, climates, 50, "minecraft:deep_frozen_ocean", 0.5f, 0.5f, FROZEN_WATER);
        register(ids, climates, 127, "minecraft:the_void", 0.5f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 129, "minecraft:sunflower_plains", 0.8f, 0.4f, DEFAULT_WATER);
        register(ids, climates, 130, "minecraft:desert_lakes", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 131, "minecraft:gravelly_mountains", 0.2f, 0.3f, DEFAULT_WATER);
        register(ids, climates, 132, "minecraft:flower_forest", 0.7f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 133, "minecraft:taiga_mountains", 0.25f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 134, "minecraft:swamp_hills", 0.8f, 0.9f, SWAMP_WATER, "swamp", null, SWAMP_FOLIAGE);
        register(ids, climates, 140, "minecraft:ice_spikes", 0.0f, 0.5f, DEFAULT_WATER);
        register(ids, climates, 149, "minecraft:modified_jungle", 0.95f, 0.9f, DEFAULT_WATER);
        register(ids, climates, 151, "minecraft:modified_jungle_edge", 0.95f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 155, "minecraft:tall_birch_forest", 0.6f, 0.6f, DEFAULT_WATER);
        register(ids, climates, 156, "minecraft:tall_birch_hills", 0.6f, 0.6f, DEFAULT_WATER);
        register(ids, climates, 157, "minecraft:dark_forest_hills", 0.7f, 0.8f, DEFAULT_WATER, "dark_forest", null, null);
        register(ids, climates, 158, "minecraft:snowy_taiga_mountains", -0.5f, 0.4f, COLD_WATER);
        register(ids, climates, 160, "minecraft:giant_spruce_taiga", 0.25f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 161, "minecraft:giant_spruce_taiga_hills", 0.25f, 0.8f, DEFAULT_WATER);
        register(ids, climates, 162, "minecraft:modified_gravelly_mountains", 0.2f, 0.3f, DEFAULT_WATER);
        register(ids, climates, 163, "minecraft:shattered_savanna", 1.1f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 164, "minecraft:shattered_savanna_plateau", 1.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 165, "minecraft:eroded_badlands", 2.0f, 0.0f, DEFAULT_WATER, "none", BADLANDS_GRASS, BADLANDS_FOLIAGE);
        register(ids, climates, 166, "minecraft:modified_wooded_badlands_plateau", 2.0f, 0.0f, DEFAULT_WATER, "none", BADLANDS_GRASS, BADLANDS_FOLIAGE);
        register(ids, climates, 167, "minecraft:modified_badlands_plateau", 2.0f, 0.0f, DEFAULT_WATER, "none", BADLANDS_GRASS, BADLANDS_FOLIAGE);
        register(ids, climates, 168, "minecraft:bamboo_jungle", 0.95f, 0.9f, DEFAULT_WATER);
        register(ids, climates, 169, "minecraft:bamboo_jungle_hills", 0.95f, 0.9f, DEFAULT_WATER);
        register(ids, climates, 170, "minecraft:soul_sand_valley", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 171, "minecraft:crimson_forest", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 172, "minecraft:warped_forest", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 173, "minecraft:basalt_deltas", 2.0f, 0.0f, DEFAULT_WATER);
        register(ids, climates, 174, "minecraft:dripstone_caves", 0.8f, 0.4f, DEFAULT_WATER);
        register(ids, climates, 175, "minecraft:lush_caves", 0.5f, 0.5f, DEFAULT_WATER);

        // Name aliases across 1.13–1.19.2 and 1.18+ named-only biomes
        climates.put("minecraft:nether", climates.get("minecraft:nether_wastes"));
        climates.put("minecraft:snowy_plains", climates.get("minecraft:snowy_tundra"));
        climates.put("minecraft:sparse_jungle", climates.get("minecraft:jungle_edge"));
        climates.put("minecraft:stony_shore", climates.get("minecraft:stone_shore"));
        climates.put("minecraft:old_growth_pine_taiga", climates.get("minecraft:giant_tree_taiga"));
        climates.put("minecraft:old_growth_spruce_taiga", climates.get("minecraft:giant_spruce_taiga"));
        climates.put("minecraft:old_growth_birch_forest", climates.get("minecraft:tall_birch_forest"));
        climates.put("minecraft:windswept_hills", climates.get("minecraft:mountains"));
        climates.put("minecraft:windswept_gravelly_hills", climates.get("minecraft:gravelly_mountains"));
        climates.put("minecraft:windswept_forest", climates.get("minecraft:wooded_mountains"));
        climates.put("minecraft:windswept_savanna", climates.get("minecraft:shattered_savanna"));
        climates.put("minecraft:wooded_badlands", climates.get("minecraft:wooded_badlands_plateau"));
        climates.put("minecraft:meadow", new Climate(0.5f, 0.8f, 0x0E4ECF, "none", null, null));
        climates.put("minecraft:grove", new Climate(-0.2f, 0.8f, DEFAULT_WATER, "none", null, null));
        climates.put("minecraft:snowy_slopes", new Climate(-0.3f, 0.9f, DEFAULT_WATER, "none", null, null));
        climates.put("minecraft:frozen_peaks", new Climate(-0.7f, 0.9f, DEFAULT_WATER, "none", null, null));
        climates.put("minecraft:jagged_peaks", new Climate(-0.7f, 0.9f, DEFAULT_WATER, "none", null, null));
        climates.put("minecraft:stony_peaks", new Climate(1.0f, 0.3f, DEFAULT_WATER, "none", null, null));
        climates.put("minecraft:mangrove_swamp", new Climate(0.8f, 0.9f, 0x3A7A6A, "swamp", null, 0x8DB127));
        climates.put("minecraft:deep_dark", new Climate(0.8f, 0.4f, DEFAULT_WATER, "none", null, null));

        ID_TO_NAME = Map.copyOf(ids);
        NAME_TO_CLIMATE = Map.copyOf(climates);
    }

    private VanillaLegacyBiomes() {
    }

    private static void register(Map<Integer, String> ids, Map<String, Climate> climates,
                                 int id, String name, float temperature, float downfall, int waterColor) {
        register(ids, climates, id, name, temperature, downfall, waterColor, "none", null, null);
    }

    private static void register(Map<Integer, String> ids, Map<String, Climate> climates,
                                 int id, String name, float temperature, float downfall, int waterColor,
                                 String grassColorModifier, Integer grassColorOverride, Integer foliageColorOverride) {
        ids.put(id, name);
        climates.put(name, new Climate(temperature, downfall, waterColor, grassColorModifier,
                grassColorOverride, foliageColorOverride));
    }

    public static Optional<String> name(int numericId) {
        return Optional.ofNullable(ID_TO_NAME.get(numericId));
    }

    public static Optional<Climate> climate(String biomeId) {
        if (biomeId == null) return Optional.empty();
        return Optional.ofNullable(NAME_TO_CLIMATE.get(biomeId));
    }
}
