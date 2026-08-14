package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.voxel.data.BlockState;

import java.util.Set;

public final class BlockClassifier {
    private static final Set<String> AIR = Set.of(
            "minecraft:air",
            "minecraft:cave_air",
            "minecraft:void_air");
    private static final Set<String> FLUID = Set.of(
            "minecraft:water",
            "minecraft:lava");
    private static final Set<String> TRANSLUCENT = Set.of(
            "minecraft:glass",
            "minecraft:tinted_glass",
            "minecraft:ice",
            "minecraft:frosted_ice",
            "minecraft:slime_block",
            "minecraft:honey_block",
            "minecraft:nether_portal",
            "minecraft:end_gateway",
            "minecraft:end_portal",
            "minecraft:white_stained_glass",
            "minecraft:orange_stained_glass",
            "minecraft:magenta_stained_glass",
            "minecraft:light_blue_stained_glass",
            "minecraft:yellow_stained_glass",
            "minecraft:lime_stained_glass",
            "minecraft:pink_stained_glass",
            "minecraft:gray_stained_glass",
            "minecraft:light_gray_stained_glass",
            "minecraft:cyan_stained_glass",
            "minecraft:purple_stained_glass",
            "minecraft:blue_stained_glass",
            "minecraft:brown_stained_glass",
            "minecraft:green_stained_glass",
            "minecraft:red_stained_glass",
            "minecraft:black_stained_glass",
            "minecraft:white_stained_glass_pane",
            "minecraft:orange_stained_glass_pane",
            "minecraft:magenta_stained_glass_pane",
            "minecraft:light_blue_stained_glass_pane",
            "minecraft:yellow_stained_glass_pane",
            "minecraft:lime_stained_glass_pane",
            "minecraft:pink_stained_glass_pane",
            "minecraft:gray_stained_glass_pane",
            "minecraft:light_gray_stained_glass_pane",
            "minecraft:cyan_stained_glass_pane",
            "minecraft:purple_stained_glass_pane",
            "minecraft:blue_stained_glass_pane",
            "minecraft:brown_stained_glass_pane",
            "minecraft:green_stained_glass_pane",
            "minecraft:red_stained_glass_pane",
            "minecraft:black_stained_glass_pane");
    private static final Set<String> CUTOUT = Set.of(
            "minecraft:oak_leaves",
            "minecraft:spruce_leaves",
            "minecraft:birch_leaves",
            "minecraft:jungle_leaves",
            "minecraft:acacia_leaves",
            "minecraft:dark_oak_leaves",
            "minecraft:mangrove_leaves",
            "minecraft:cherry_leaves",
            "minecraft:azalea_leaves",
            "minecraft:flowering_azalea_leaves",
            "minecraft:short_grass",
            "minecraft:tall_grass",
            "minecraft:fern",
            "minecraft:large_fern",
            "minecraft:vine",
            "minecraft:torch",
            "minecraft:soul_torch",
            "minecraft:redstone_torch",
            "minecraft:rail",
            "minecraft:powered_rail",
            "minecraft:detector_rail",
            "minecraft:activator_rail");

    private BlockClassifier() {
    }

    public static BlockRenderClass classify(BlockState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        String name = state.name();
        if (AIR.contains(name)) {
            return new BlockRenderClass(BlockRenderClass.Kind.AIR, null, 0, 0.0f);
        }
        if (FLUID.contains(name)) {
            int rgb = "minecraft:water".equals(name) ? 0x3F76E4 : 0xF36B21;
            return new BlockRenderClass(
                    BlockRenderClass.Kind.FLUID, RenderLayer.FLUID, rgb, 0.72f);
        }
        if (TRANSLUCENT.contains(name)) {
            return new BlockRenderClass(
                    BlockRenderClass.Kind.TRANSLUCENT,
                    RenderLayer.TRANSLUCENT,
                    BlockColorPalette.resolve(name).rgb(),
                    0.52f);
        }
        if (CUTOUT.contains(name)) {
            return new BlockRenderClass(
                    BlockRenderClass.Kind.CUTOUT,
                    RenderLayer.CUTOUT,
                    BlockColorPalette.resolve(name).rgb(),
                    1.0f);
        }
        if (name.startsWith("minecraft:")) {
            return new BlockRenderClass(
                    BlockRenderClass.Kind.OPAQUE,
                    RenderLayer.OPAQUE,
                    BlockColorPalette.resolve(name).rgb(),
                    1.0f);
        }
        return new BlockRenderClass(
                BlockRenderClass.Kind.FALLBACK,
                RenderLayer.FALLBACK,
                0xE83EED,
                1.0f);
    }

}
