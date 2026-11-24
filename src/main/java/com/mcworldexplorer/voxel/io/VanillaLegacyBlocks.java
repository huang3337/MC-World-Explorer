package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.voxel.data.BlockState;

import java.util.Map;
import java.util.Optional;

/**
 * Complete 1.2.1–1.12.2 vanilla numeric block ID (0..255) and metadata mapping table.
 * Normalizes known pre-flattening {@code minecraft:*} blocks into modern canonical names
 * so 2D surface coloring, portal extraction, and 3D simplified classification work consistently,
 * while preserving real Mod IDs and deterministic {@code legacy:block_<id>} identities.
 */
public final class VanillaLegacyBlocks {
    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
    };

    private static final String[] WOOD_TYPES = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak"
    };

    private static final String[] VANILLA_NAMES = new String[256];

    static {
        VANILLA_NAMES[0] = "minecraft:air";
        VANILLA_NAMES[1] = "minecraft:stone";
        VANILLA_NAMES[2] = "minecraft:grass";
        VANILLA_NAMES[3] = "minecraft:dirt";
        VANILLA_NAMES[4] = "minecraft:cobblestone";
        VANILLA_NAMES[5] = "minecraft:planks";
        VANILLA_NAMES[6] = "minecraft:sapling";
        VANILLA_NAMES[7] = "minecraft:bedrock";
        VANILLA_NAMES[8] = "minecraft:flowing_water";
        VANILLA_NAMES[9] = "minecraft:water";
        VANILLA_NAMES[10] = "minecraft:flowing_lava";
        VANILLA_NAMES[11] = "minecraft:lava";
        VANILLA_NAMES[12] = "minecraft:sand";
        VANILLA_NAMES[13] = "minecraft:gravel";
        VANILLA_NAMES[14] = "minecraft:gold_ore";
        VANILLA_NAMES[15] = "minecraft:iron_ore";
        VANILLA_NAMES[16] = "minecraft:coal_ore";
        VANILLA_NAMES[17] = "minecraft:log";
        VANILLA_NAMES[18] = "minecraft:leaves";
        VANILLA_NAMES[19] = "minecraft:sponge";
        VANILLA_NAMES[20] = "minecraft:glass";
        VANILLA_NAMES[21] = "minecraft:lapis_ore";
        VANILLA_NAMES[22] = "minecraft:lapis_block";
        VANILLA_NAMES[23] = "minecraft:dispenser";
        VANILLA_NAMES[24] = "minecraft:sandstone";
        VANILLA_NAMES[25] = "minecraft:noteblock";
        VANILLA_NAMES[26] = "minecraft:bed";
        VANILLA_NAMES[27] = "minecraft:golden_rail";
        VANILLA_NAMES[28] = "minecraft:detector_rail";
        VANILLA_NAMES[29] = "minecraft:sticky_piston";
        VANILLA_NAMES[30] = "minecraft:web";
        VANILLA_NAMES[31] = "minecraft:tallgrass";
        VANILLA_NAMES[32] = "minecraft:deadbush";
        VANILLA_NAMES[33] = "minecraft:piston";
        VANILLA_NAMES[34] = "minecraft:piston_head";
        VANILLA_NAMES[35] = "minecraft:wool";
        VANILLA_NAMES[36] = "minecraft:piston_extension";
        VANILLA_NAMES[37] = "minecraft:yellow_flower";
        VANILLA_NAMES[38] = "minecraft:red_flower";
        VANILLA_NAMES[39] = "minecraft:brown_mushroom";
        VANILLA_NAMES[40] = "minecraft:red_mushroom";
        VANILLA_NAMES[41] = "minecraft:gold_block";
        VANILLA_NAMES[42] = "minecraft:iron_block";
        VANILLA_NAMES[43] = "minecraft:double_stone_slab";
        VANILLA_NAMES[44] = "minecraft:stone_slab";
        VANILLA_NAMES[45] = "minecraft:brick_block";
        VANILLA_NAMES[46] = "minecraft:tnt";
        VANILLA_NAMES[47] = "minecraft:bookshelf";
        VANILLA_NAMES[48] = "minecraft:mossy_cobblestone";
        VANILLA_NAMES[49] = "minecraft:obsidian";
        VANILLA_NAMES[50] = "minecraft:torch";
        VANILLA_NAMES[51] = "minecraft:fire";
        VANILLA_NAMES[52] = "minecraft:mob_spawner";
        VANILLA_NAMES[53] = "minecraft:oak_stairs";
        VANILLA_NAMES[54] = "minecraft:chest";
        VANILLA_NAMES[55] = "minecraft:redstone_wire";
        VANILLA_NAMES[56] = "minecraft:diamond_ore";
        VANILLA_NAMES[57] = "minecraft:diamond_block";
        VANILLA_NAMES[58] = "minecraft:crafting_table";
        VANILLA_NAMES[59] = "minecraft:wheat";
        VANILLA_NAMES[60] = "minecraft:farmland";
        VANILLA_NAMES[61] = "minecraft:furnace";
        VANILLA_NAMES[62] = "minecraft:lit_furnace";
        VANILLA_NAMES[63] = "minecraft:standing_sign";
        VANILLA_NAMES[64] = "minecraft:wooden_door";
        VANILLA_NAMES[65] = "minecraft:ladder";
        VANILLA_NAMES[66] = "minecraft:rail";
        VANILLA_NAMES[67] = "minecraft:stone_stairs";
        VANILLA_NAMES[68] = "minecraft:wall_sign";
        VANILLA_NAMES[69] = "minecraft:lever";
        VANILLA_NAMES[70] = "minecraft:stone_pressure_plate";
        VANILLA_NAMES[71] = "minecraft:iron_door";
        VANILLA_NAMES[72] = "minecraft:wooden_pressure_plate";
        VANILLA_NAMES[73] = "minecraft:redstone_ore";
        VANILLA_NAMES[74] = "minecraft:lit_redstone_ore";
        VANILLA_NAMES[75] = "minecraft:unlit_redstone_torch";
        VANILLA_NAMES[76] = "minecraft:redstone_torch";
        VANILLA_NAMES[77] = "minecraft:stone_button";
        VANILLA_NAMES[78] = "minecraft:snow_layer";
        VANILLA_NAMES[79] = "minecraft:ice";
        VANILLA_NAMES[80] = "minecraft:snow";
        VANILLA_NAMES[81] = "minecraft:cactus";
        VANILLA_NAMES[82] = "minecraft:clay";
        VANILLA_NAMES[83] = "minecraft:reeds";
        VANILLA_NAMES[84] = "minecraft:jukebox";
        VANILLA_NAMES[85] = "minecraft:fence";
        VANILLA_NAMES[86] = "minecraft:pumpkin";
        VANILLA_NAMES[87] = "minecraft:netherrack";
        VANILLA_NAMES[88] = "minecraft:soul_sand";
        VANILLA_NAMES[89] = "minecraft:glowstone";
        VANILLA_NAMES[90] = "minecraft:portal";
        VANILLA_NAMES[91] = "minecraft:lit_pumpkin";
        VANILLA_NAMES[92] = "minecraft:cake";
        VANILLA_NAMES[93] = "minecraft:unpowered_repeater";
        VANILLA_NAMES[94] = "minecraft:powered_repeater";
        VANILLA_NAMES[95] = "minecraft:stained_glass";
        VANILLA_NAMES[96] = "minecraft:trapdoor";
        VANILLA_NAMES[97] = "minecraft:monster_egg";
        VANILLA_NAMES[98] = "minecraft:stonebrick";
        VANILLA_NAMES[99] = "minecraft:brown_mushroom_block";
        VANILLA_NAMES[100] = "minecraft:red_mushroom_block";
        VANILLA_NAMES[101] = "minecraft:iron_bars";
        VANILLA_NAMES[102] = "minecraft:glass_pane";
        VANILLA_NAMES[103] = "minecraft:melon_block";
        VANILLA_NAMES[104] = "minecraft:pumpkin_stem";
        VANILLA_NAMES[105] = "minecraft:melon_stem";
        VANILLA_NAMES[106] = "minecraft:vine";
        VANILLA_NAMES[107] = "minecraft:fence_gate";
        VANILLA_NAMES[108] = "minecraft:brick_stairs";
        VANILLA_NAMES[109] = "minecraft:stone_brick_stairs";
        VANILLA_NAMES[110] = "minecraft:mycelium";
        VANILLA_NAMES[111] = "minecraft:waterlily";
        VANILLA_NAMES[112] = "minecraft:nether_brick";
        VANILLA_NAMES[113] = "minecraft:nether_brick_fence";
        VANILLA_NAMES[114] = "minecraft:nether_brick_stairs";
        VANILLA_NAMES[115] = "minecraft:nether_wart";
        VANILLA_NAMES[116] = "minecraft:enchanting_table";
        VANILLA_NAMES[117] = "minecraft:brewing_stand";
        VANILLA_NAMES[118] = "minecraft:cauldron";
        VANILLA_NAMES[119] = "minecraft:end_portal";
        VANILLA_NAMES[120] = "minecraft:end_portal_frame";
        VANILLA_NAMES[121] = "minecraft:end_stone";
        VANILLA_NAMES[122] = "minecraft:dragon_egg";
        VANILLA_NAMES[123] = "minecraft:redstone_lamp";
        VANILLA_NAMES[124] = "minecraft:lit_redstone_lamp";
        VANILLA_NAMES[125] = "minecraft:double_wooden_slab";
        VANILLA_NAMES[126] = "minecraft:wooden_slab";
        VANILLA_NAMES[127] = "minecraft:cocoa";
        VANILLA_NAMES[128] = "minecraft:sandstone_stairs";
        VANILLA_NAMES[129] = "minecraft:emerald_ore";
        VANILLA_NAMES[130] = "minecraft:ender_chest";
        VANILLA_NAMES[131] = "minecraft:tripwire_hook";
        VANILLA_NAMES[132] = "minecraft:tripwire";
        VANILLA_NAMES[133] = "minecraft:emerald_block";
        VANILLA_NAMES[134] = "minecraft:spruce_stairs";
        VANILLA_NAMES[135] = "minecraft:birch_stairs";
        VANILLA_NAMES[136] = "minecraft:jungle_stairs";
        VANILLA_NAMES[137] = "minecraft:command_block";
        VANILLA_NAMES[138] = "minecraft:beacon";
        VANILLA_NAMES[139] = "minecraft:cobblestone_wall";
        VANILLA_NAMES[140] = "minecraft:flower_pot";
        VANILLA_NAMES[141] = "minecraft:carrots";
        VANILLA_NAMES[142] = "minecraft:potatoes";
        VANILLA_NAMES[143] = "minecraft:wooden_button";
        VANILLA_NAMES[144] = "minecraft:skull";
        VANILLA_NAMES[145] = "minecraft:anvil";
        VANILLA_NAMES[146] = "minecraft:trapped_chest";
        VANILLA_NAMES[147] = "minecraft:light_weighted_pressure_plate";
        VANILLA_NAMES[148] = "minecraft:heavy_weighted_pressure_plate";
        VANILLA_NAMES[149] = "minecraft:unpowered_comparator";
        VANILLA_NAMES[150] = "minecraft:powered_comparator";
        VANILLA_NAMES[151] = "minecraft:daylight_detector";
        VANILLA_NAMES[152] = "minecraft:redstone_block";
        VANILLA_NAMES[153] = "minecraft:quartz_ore";
        VANILLA_NAMES[154] = "minecraft:hopper";
        VANILLA_NAMES[155] = "minecraft:quartz_block";
        VANILLA_NAMES[156] = "minecraft:quartz_stairs";
        VANILLA_NAMES[157] = "minecraft:activator_rail";
        VANILLA_NAMES[158] = "minecraft:dropper";
        VANILLA_NAMES[159] = "minecraft:stained_hardened_clay";
        VANILLA_NAMES[160] = "minecraft:stained_glass_pane";
        VANILLA_NAMES[161] = "minecraft:leaves2";
        VANILLA_NAMES[162] = "minecraft:log2";
        VANILLA_NAMES[163] = "minecraft:acacia_stairs";
        VANILLA_NAMES[164] = "minecraft:dark_oak_stairs";
        VANILLA_NAMES[165] = "minecraft:slime";
        VANILLA_NAMES[166] = "minecraft:barrier";
        VANILLA_NAMES[167] = "minecraft:iron_trapdoor";
        VANILLA_NAMES[168] = "minecraft:prismarine";
        VANILLA_NAMES[169] = "minecraft:sea_lantern";
        VANILLA_NAMES[170] = "minecraft:hay_block";
        VANILLA_NAMES[171] = "minecraft:carpet";
        VANILLA_NAMES[172] = "minecraft:hardened_clay";
        VANILLA_NAMES[173] = "minecraft:coal_block";
        VANILLA_NAMES[174] = "minecraft:packed_ice";
        VANILLA_NAMES[175] = "minecraft:double_plant";
        VANILLA_NAMES[176] = "minecraft:standing_banner";
        VANILLA_NAMES[177] = "minecraft:wall_banner";
        VANILLA_NAMES[178] = "minecraft:daylight_detector_inverted";
        VANILLA_NAMES[179] = "minecraft:red_sandstone";
        VANILLA_NAMES[180] = "minecraft:red_sandstone_stairs";
        VANILLA_NAMES[181] = "minecraft:double_stone_slab2";
        VANILLA_NAMES[182] = "minecraft:stone_slab2";
        VANILLA_NAMES[183] = "minecraft:spruce_fence_gate";
        VANILLA_NAMES[184] = "minecraft:birch_fence_gate";
        VANILLA_NAMES[185] = "minecraft:jungle_fence_gate";
        VANILLA_NAMES[186] = "minecraft:dark_oak_fence_gate";
        VANILLA_NAMES[187] = "minecraft:acacia_fence_gate";
        VANILLA_NAMES[188] = "minecraft:spruce_fence";
        VANILLA_NAMES[189] = "minecraft:birch_fence";
        VANILLA_NAMES[190] = "minecraft:jungle_fence";
        VANILLA_NAMES[191] = "minecraft:dark_oak_fence";
        VANILLA_NAMES[192] = "minecraft:acacia_fence";
        VANILLA_NAMES[193] = "minecraft:spruce_door";
        VANILLA_NAMES[194] = "minecraft:birch_door";
        VANILLA_NAMES[195] = "minecraft:jungle_door";
        VANILLA_NAMES[196] = "minecraft:acacia_door";
        VANILLA_NAMES[197] = "minecraft:dark_oak_door";
        VANILLA_NAMES[198] = "minecraft:end_rod";
        VANILLA_NAMES[199] = "minecraft:chorus_plant";
        VANILLA_NAMES[200] = "minecraft:chorus_flower";
        VANILLA_NAMES[201] = "minecraft:purpur_block";
        VANILLA_NAMES[202] = "minecraft:purpur_pillar";
        VANILLA_NAMES[203] = "minecraft:purpur_stairs";
        VANILLA_NAMES[204] = "minecraft:purpur_double_slab";
        VANILLA_NAMES[205] = "minecraft:purpur_slab";
        VANILLA_NAMES[206] = "minecraft:end_bricks";
        VANILLA_NAMES[207] = "minecraft:beetroots";
        VANILLA_NAMES[208] = "minecraft:grass_path";
        VANILLA_NAMES[209] = "minecraft:end_gateway";
        VANILLA_NAMES[210] = "minecraft:repeating_command_block";
        VANILLA_NAMES[211] = "minecraft:chain_command_block";
        VANILLA_NAMES[212] = "minecraft:frosted_ice";
        VANILLA_NAMES[213] = "minecraft:magma";
        VANILLA_NAMES[214] = "minecraft:nether_wart_block";
        VANILLA_NAMES[215] = "minecraft:red_nether_brick";
        VANILLA_NAMES[216] = "minecraft:bone_block";
        VANILLA_NAMES[217] = "minecraft:structure_void";
        VANILLA_NAMES[218] = "minecraft:observer";
        VANILLA_NAMES[219] = "minecraft:white_shulker_box";
        VANILLA_NAMES[220] = "minecraft:orange_shulker_box";
        VANILLA_NAMES[221] = "minecraft:magenta_shulker_box";
        VANILLA_NAMES[222] = "minecraft:light_blue_shulker_box";
        VANILLA_NAMES[223] = "minecraft:yellow_shulker_box";
        VANILLA_NAMES[224] = "minecraft:lime_shulker_box";
        VANILLA_NAMES[225] = "minecraft:pink_shulker_box";
        VANILLA_NAMES[226] = "minecraft:gray_shulker_box";
        VANILLA_NAMES[227] = "minecraft:silver_shulker_box";
        VANILLA_NAMES[228] = "minecraft:cyan_shulker_box";
        VANILLA_NAMES[229] = "minecraft:purple_shulker_box";
        VANILLA_NAMES[230] = "minecraft:blue_shulker_box";
        VANILLA_NAMES[231] = "minecraft:brown_shulker_box";
        VANILLA_NAMES[232] = "minecraft:green_shulker_box";
        VANILLA_NAMES[233] = "minecraft:red_shulker_box";
        VANILLA_NAMES[234] = "minecraft:black_shulker_box";
        VANILLA_NAMES[235] = "minecraft:white_glazed_terracotta";
        VANILLA_NAMES[236] = "minecraft:orange_glazed_terracotta";
        VANILLA_NAMES[237] = "minecraft:magenta_glazed_terracotta";
        VANILLA_NAMES[238] = "minecraft:light_blue_glazed_terracotta";
        VANILLA_NAMES[239] = "minecraft:yellow_glazed_terracotta";
        VANILLA_NAMES[240] = "minecraft:lime_glazed_terracotta";
        VANILLA_NAMES[241] = "minecraft:pink_glazed_terracotta";
        VANILLA_NAMES[242] = "minecraft:gray_glazed_terracotta";
        VANILLA_NAMES[243] = "minecraft:silver_glazed_terracotta";
        VANILLA_NAMES[244] = "minecraft:cyan_glazed_terracotta";
        VANILLA_NAMES[245] = "minecraft:purple_glazed_terracotta";
        VANILLA_NAMES[246] = "minecraft:blue_glazed_terracotta";
        VANILLA_NAMES[247] = "minecraft:brown_glazed_terracotta";
        VANILLA_NAMES[248] = "minecraft:green_glazed_terracotta";
        VANILLA_NAMES[249] = "minecraft:red_glazed_terracotta";
        VANILLA_NAMES[250] = "minecraft:black_glazed_terracotta";
        VANILLA_NAMES[251] = "minecraft:concrete";
        VANILLA_NAMES[252] = "minecraft:concrete_powder";
        VANILLA_NAMES[255] = "minecraft:structure_block";
    }

    private VanillaLegacyBlocks() {
    }

    public static Optional<String> vanillaLegacyName(int blockId) {
        if (blockId < 0 || blockId >= VANILLA_NAMES.length) {
            return Optional.empty();
        }
        return Optional.ofNullable(VANILLA_NAMES[blockId]);
    }

    /**
     * Resolves a legacy {@code (blockId, meta)} pair using the save's FML ID map first,
     * falling back to the vanilla 0..255 table, and finally to deterministic {@code legacy:block_<id>}.
     */
    public static BlockState resolve(int blockId, int meta, SavedLegacyIdMap idMap, boolean scene) {
        int clampedMeta = meta & 0x0F;
        Optional<String> mapped = idMap == null ? Optional.empty() : idMap.resolveBlockName(blockId);
        String rawName;
        if (mapped.isPresent()) {
            rawName = mapped.get();
        } else if (blockId >= 0 && blockId < VANILLA_NAMES.length && VANILLA_NAMES[blockId] != null) {
            rawName = VANILLA_NAMES[blockId];
        } else {
            return new BlockState(
                    "legacy:block_" + blockId,
                    scene ? Map.of("data", Integer.toString(clampedMeta)) : Map.of());
        }

        if ("minecraft:air".equals(rawName)) {
            return BlockState.AIR;
        }

        String resolvedName = rawName.startsWith("minecraft:")
                ? normalizeVanillaName(rawName, clampedMeta)
                : rawName;

        if ("minecraft:air".equals(resolvedName)) {
            return BlockState.AIR;
        }
        return new BlockState(
                resolvedName,
                scene ? Map.of("data", Integer.toString(clampedMeta)) : Map.of());
    }

    /**
     * Normalizes a pre-flattening {@code minecraft:*} block name and metadata (0..15)
     * into its canonical flattened {@code minecraft:*} name.
     */
    public static String normalizeVanillaName(String legacyName, int meta) {
        int m = meta & 0x0F;
        return switch (legacyName) {
            case "minecraft:stone" -> switch (m) {
                case 1 -> "minecraft:granite";
                case 2 -> "minecraft:polished_granite";
                case 3 -> "minecraft:diorite";
                case 4 -> "minecraft:polished_diorite";
                case 5 -> "minecraft:andesite";
                case 6 -> "minecraft:polished_andesite";
                default -> "minecraft:stone";
            };
            case "minecraft:grass" -> "minecraft:grass_block";
            case "minecraft:dirt" -> switch (m) {
                case 1 -> "minecraft:coarse_dirt";
                case 2 -> "minecraft:podzol";
                default -> "minecraft:dirt";
            };
            case "minecraft:planks" -> "minecraft:" + woodType(m) + "_planks";
            case "minecraft:sapling" -> "minecraft:" + woodType(m & 0x07) + "_sapling";
            case "minecraft:flowing_water", "minecraft:water" -> "minecraft:water";
            case "minecraft:flowing_lava", "minecraft:lava" -> "minecraft:lava";
            case "minecraft:sand" -> m == 1 ? "minecraft:red_sand" : "minecraft:sand";
            case "minecraft:log" -> switch (m & 0x03) {
                case 1 -> "minecraft:spruce_log";
                case 2 -> "minecraft:birch_log";
                case 3 -> "minecraft:jungle_log";
                default -> "minecraft:oak_log";
            };
            case "minecraft:log2" -> (m & 0x03) == 1 ? "minecraft:dark_oak_log" : "minecraft:acacia_log";
            case "minecraft:leaves" -> switch (m & 0x03) {
                case 1 -> "minecraft:spruce_leaves";
                case 2 -> "minecraft:birch_leaves";
                case 3 -> "minecraft:jungle_leaves";
                default -> "minecraft:oak_leaves";
            };
            case "minecraft:leaves2" -> (m & 0x03) == 1 ? "minecraft:dark_oak_leaves" : "minecraft:acacia_leaves";
            case "minecraft:sponge" -> m == 1 ? "minecraft:wet_sponge" : "minecraft:sponge";
            case "minecraft:sandstone" -> switch (m) {
                case 1 -> "minecraft:chiseled_sandstone";
                case 2 -> "minecraft:cut_sandstone";
                default -> "minecraft:sandstone";
            };
            case "minecraft:red_sandstone" -> switch (m) {
                case 1 -> "minecraft:chiseled_red_sandstone";
                case 2 -> "minecraft:cut_red_sandstone";
                default -> "minecraft:red_sandstone";
            };
            case "minecraft:noteblock" -> "minecraft:note_block";
            case "minecraft:bed" -> "minecraft:red_bed";
            case "minecraft:golden_rail" -> "minecraft:powered_rail";
            case "minecraft:web" -> "minecraft:cobweb";
            case "minecraft:tallgrass" -> switch (m) {
                case 0 -> "minecraft:dead_bush";
                case 2 -> "minecraft:fern";
                default -> "minecraft:short_grass";
            };
            case "minecraft:deadbush" -> "minecraft:dead_bush";
            case "minecraft:wool" -> "minecraft:" + COLORS[m] + "_wool";
            case "minecraft:yellow_flower" -> "minecraft:dandelion";
            case "minecraft:red_flower" -> switch (m) {
                case 1 -> "minecraft:blue_orchid";
                case 2 -> "minecraft:allium";
                case 3 -> "minecraft:azure_bluet";
                case 4 -> "minecraft:red_tulip";
                case 5 -> "minecraft:orange_tulip";
                case 6 -> "minecraft:white_tulip";
                case 7 -> "minecraft:pink_tulip";
                case 8 -> "minecraft:oxeye_daisy";
                default -> "minecraft:poppy";
            };
            case "minecraft:double_stone_slab", "minecraft:stone_slab" -> switch (m & 0x07) {
                case 1 -> "minecraft:sandstone_slab";
                case 2 -> "minecraft:petrified_oak_slab";
                case 3 -> "minecraft:cobblestone_slab";
                case 4 -> "minecraft:brick_slab";
                case 5 -> "minecraft:stone_brick_slab";
                case 6 -> "minecraft:nether_brick_slab";
                case 7 -> "minecraft:quartz_slab";
                default -> "minecraft:smooth_stone_slab";
            };
            case "minecraft:double_stone_slab2", "minecraft:stone_slab2" -> "minecraft:red_sandstone_slab";
            case "minecraft:double_wooden_slab", "minecraft:wooden_slab" -> "minecraft:" + woodType(m & 0x07) + "_slab";
            case "minecraft:brick_block" -> "minecraft:bricks";
            case "minecraft:mob_spawner" -> "minecraft:spawner";
            case "minecraft:lit_furnace" -> "minecraft:furnace";
            case "minecraft:standing_sign" -> "minecraft:oak_sign";
            case "minecraft:wall_sign" -> "minecraft:oak_wall_sign";
            case "minecraft:wooden_door" -> "minecraft:oak_door";
            case "minecraft:stone_stairs" -> "minecraft:cobblestone_stairs";
            case "minecraft:wooden_pressure_plate" -> "minecraft:oak_pressure_plate";
            case "minecraft:lit_redstone_ore" -> "minecraft:redstone_ore";
            case "minecraft:unlit_redstone_torch" -> "minecraft:redstone_torch";
            case "minecraft:snow_layer" -> "minecraft:snow";
            case "minecraft:snow" -> "minecraft:snow_block";
            case "minecraft:reeds" -> "minecraft:sugar_cane";
            case "minecraft:fence" -> "minecraft:oak_fence";
            case "minecraft:portal" -> "minecraft:nether_portal";
            case "minecraft:lit_pumpkin" -> "minecraft:jack_o_lantern";
            case "minecraft:unpowered_repeater", "minecraft:powered_repeater" -> "minecraft:repeater";
            case "minecraft:stained_glass" -> "minecraft:" + COLORS[m] + "_stained_glass";
            case "minecraft:trapdoor" -> "minecraft:oak_trapdoor";
            case "minecraft:monster_egg" -> switch (m) {
                case 1 -> "minecraft:infested_cobblestone";
                case 2 -> "minecraft:infested_stone_bricks";
                case 3 -> "minecraft:infested_mossy_stone_bricks";
                case 4 -> "minecraft:infested_cracked_stone_bricks";
                case 5 -> "minecraft:infested_chiseled_stone_bricks";
                default -> "minecraft:infested_stone";
            };
            case "minecraft:stonebrick" -> switch (m) {
                case 1 -> "minecraft:mossy_stone_bricks";
                case 2 -> "minecraft:cracked_stone_bricks";
                case 3 -> "minecraft:chiseled_stone_bricks";
                default -> "minecraft:stone_bricks";
            };
            case "minecraft:melon_block" -> "minecraft:melon";
            case "minecraft:fence_gate" -> "minecraft:oak_fence_gate";
            case "minecraft:waterlily" -> "minecraft:lily_pad";
            case "minecraft:nether_brick" -> "minecraft:nether_bricks";
            case "minecraft:lit_redstone_lamp" -> "minecraft:redstone_lamp";
            case "minecraft:wooden_button" -> "minecraft:oak_button";
            case "minecraft:unpowered_comparator", "minecraft:powered_comparator" -> "minecraft:comparator";
            case "minecraft:daylight_detector_inverted" -> "minecraft:daylight_detector";
            case "minecraft:quartz_ore" -> "minecraft:nether_quartz_ore";
            case "minecraft:quartz_block" -> switch (m) {
                case 1 -> "minecraft:chiseled_quartz_block";
                case 2, 3, 4 -> "minecraft:quartz_pillar";
                default -> "minecraft:quartz_block";
            };
            case "minecraft:stained_hardened_clay" -> "minecraft:" + COLORS[m] + "_terracotta";
            case "minecraft:stained_glass_pane" -> "minecraft:" + COLORS[m] + "_stained_glass_pane";
            case "minecraft:slime" -> "minecraft:slime_block";
            case "minecraft:prismarine" -> switch (m) {
                case 1 -> "minecraft:prismarine_bricks";
                case 2 -> "minecraft:dark_prismarine";
                default -> "minecraft:prismarine";
            };
            case "minecraft:carpet" -> "minecraft:" + COLORS[m] + "_carpet";
            case "minecraft:hardened_clay" -> "minecraft:terracotta";
            case "minecraft:double_plant" -> switch (m & 0x07) {
                case 0 -> "minecraft:sunflower";
                case 1 -> "minecraft:lilac";
                case 2 -> "minecraft:tall_grass";
                case 3 -> "minecraft:large_fern";
                case 4 -> "minecraft:rose_bush";
                case 5 -> "minecraft:peony";
                default -> "minecraft:tall_grass";
            };
            case "minecraft:standing_banner" -> "minecraft:white_banner";
            case "minecraft:wall_banner" -> "minecraft:white_wall_banner";
            case "minecraft:end_bricks" -> "minecraft:end_stone_bricks";
            case "minecraft:grass_path" -> "minecraft:dirt_path";
            case "minecraft:magma" -> "minecraft:magma_block";
            case "minecraft:red_nether_brick" -> "minecraft:red_nether_bricks";
            case "minecraft:silver_shulker_box" -> "minecraft:light_gray_shulker_box";
            case "minecraft:silver_glazed_terracotta" -> "minecraft:light_gray_glazed_terracotta";
            case "minecraft:concrete" -> "minecraft:" + COLORS[m] + "_concrete";
            case "minecraft:concrete_powder" -> "minecraft:" + COLORS[m] + "_concrete_powder";
            default -> legacyName;
        };
    }

    private static String woodType(int index) {
        return index >= 0 && index < WOOD_TYPES.length ? WOOD_TYPES[index] : "oak";
    }
}
