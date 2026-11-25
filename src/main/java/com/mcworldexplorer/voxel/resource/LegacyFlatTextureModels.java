package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/**
 * Pure static geometry and {@code textures/blocks/*.png} synthesis for 1.6.1–1.7.10
 * ({@link ClientResourceArchive.ProtocolMode#LEGACY_FLAT_TEXTURE}) clients where JSON
 * {@code blockstates/} and {@code models/} do not exist. Never executes game or Mod bytecode.
 */
final class LegacyFlatTextureModels {
    private static final Uv FULL_UV = new Uv(0, 0, 16, 16);
    private static final Vec3 ZERO = new Vec3(0, 0, 0);
    private static final Vec3 FULL = new Vec3(16, 16, 16);

    private LegacyFlatTextureModels() { }

    static Optional<List<Part>> resolve(BlockState state, BlockResourceSource archive, List<Source> sources) throws IOException {
        ResourceLocation id;
        try {
            id = ResourceLocation.parse(state.name());
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        int data = parseData(state.properties().get("data"));
        if (!id.namespace().equals("minecraft")) {
            return resolveSimpleModBlock(id, state, archive, sources);
        }
        String name = id.path();
        if (Set.of("air", "cave_air", "void_air", "barrier", "light", "structure_void").contains(name)) {
            return Optional.of(List.of(new Part(id, 0, 0, false, List.of(), null)));
        }
        if (name.equals("water") || name.equals("lava") || name.equals("bubble_column")) {
            BuiltinBlockModels builtin = archive instanceof BuiltinBlockModels b
                    ? b : new BuiltinBlockModels(archive, java.util.Map.of());
            Optional<List<Part>> fluid = builtin.resolve(state);
            fluid.ifPresent(parts -> recordTextures(parts, sources));
            return fluid;
        }

        List<Part> parts = resolveVanilla(id, name, data, archive);
        if (parts == null) {
            return Optional.empty();
        }
        recordTextures(parts, sources);
        return Optional.of(parts);
    }

    private static Optional<List<Part>> resolveSimpleModBlock(ResourceLocation id, BlockState state,
                                                              BlockResourceSource archive, List<Source> sources) throws IOException {
        ResourceLocation candidate = new ResourceLocation(id.namespace(), "blocks/" + id.path());
        if (archive.read(ResourceLocation.Kind.TEXTURE, candidate).isEmpty()) {
            return Optional.empty();
        }
        for (String suffix : List.of("_top", "_bottom", "_side", "_front")) {
            ResourceLocation multiFace = new ResourceLocation(id.namespace(), "blocks/" + id.path() + suffix);
            if (archive.read(ResourceLocation.Kind.TEXTURE, multiFace).isPresent()) {
                return Optional.empty();
            }
        }
        List<Part> parts = List.of(new Part(id, 0, 0, false, cubeAll(candidate, -1), null));
        recordTextures(parts, sources);
        if (archive instanceof BuiltinBlockModels builtin) {
            builtin.note(state, "1.6–1.7 Mod 同名纹理近似映射：仅按单一同名 textures/blocks/" + id.path()
                    + ".png 生成标准六面体，未执行 Mod 渲染类");
        }
        return Optional.of(parts);
    }

    private static List<Part> resolveVanilla(ResourceLocation id, String name, int data, BlockResourceSource archive) throws IOException {
        // 16-color wool, stained clay (terracotta), stained glass, carpet
        if (name.endsWith("_wool")) {
            String color = legacyColor(name.substring(0, name.length() - 5));
            return cubePart(id, "blocks/wool_colored_" + color, null);
        }
        if (name.endsWith("_terracotta") && !name.endsWith("_glazed_terracotta")) {
            String color = legacyColor(name.substring(0, name.length() - 11));
            return cubePart(id, "blocks/hardened_clay_stained_" + color, null);
        }
        if (name.endsWith("_stained_glass")) {
            String color = legacyColor(name.substring(0, name.length() - 14));
            return cubePart(id, "blocks/glass_" + color, "minecraft:translucent");
        }
        if (name.endsWith("_carpet")) {
            String color = legacyColor(name.substring(0, name.length() - 7));
            return boxPart(id, tex("blocks/wool_colored_" + color), 0, 0, 0, 16, 1, 16, -1, null);
        }

        // Logs (6 vanilla species)
        if (name.endsWith("_log")) {
            String stem = woodStem(name.substring(0, name.length() - 4));
            ResourceLocation side = tex("blocks/log_" + stem);
            ResourceLocation top = tex("blocks/log_" + stem + "_top");
            int axisBits = data & 0x0C;
            if (axisBits == 12) {
                return List.of(new Part(id, 0, 0, false, cubeAll(side, -1), null));
            }
            int xRot = (axisBits == 4 || axisBits == 8) ? 90 : 0;
            int yRot = axisBits == 4 ? 90 : 0;
            return List.of(new Part(id, xRot, yRot, false, cubeColumn(top, top, side), null));
        }

        // Leaves (6 vanilla species, tinted via VanillaTintLayers + SavedBiomeColors)
        if (name.endsWith("_leaves")) {
            String stem = woodStem(name.substring(0, name.length() - 7));
            return List.of(new Part(id, 0, 0, false, cubeAll(tex("blocks/leaves_" + stem), 0), "minecraft:cutout_mipped"));
        }

        // Planks (6 vanilla species)
        if (name.endsWith("_planks")) {
            String stem = woodStem(name.substring(0, name.length() - 7));
            return cubePart(id, "blocks/planks_" + stem, null);
        }

        // Saplings (6 vanilla species)
        if (name.endsWith("_sapling")) {
            String stem = name.equals("dark_oak_sapling") ? "roofed_oak" : name.substring(0, name.length() - 8);
            return crossPart(id, "blocks/sapling_" + stem, -1);
        }

        // Slabs
        if (name.endsWith("_slab")) {
            return slabPart(id, name, data);
        }

        return switch (name) {
            // Standard 6-face opaque/cutout/translucent cubes
            case "stone" -> cubePart(id, "blocks/stone", null);
            case "granite" -> cubePartFallback(id, "blocks/stone_granite", "blocks/stone", archive);
            case "polished_granite" -> cubePartFallback(id, "blocks/stone_granite_smooth", "blocks/stone", archive);
            case "diorite" -> cubePartFallback(id, "blocks/stone_diorite", "blocks/stone", archive);
            case "polished_diorite" -> cubePartFallback(id, "blocks/stone_diorite_smooth", "blocks/stone", archive);
            case "andesite" -> cubePartFallback(id, "blocks/stone_andesite", "blocks/stone", archive);
            case "polished_andesite" -> cubePartFallback(id, "blocks/stone_andesite_smooth", "blocks/stone", archive);
            case "cobblestone" -> cubePart(id, "blocks/cobblestone", null);
            case "mossy_cobblestone" -> cubePart(id, "blocks/cobblestone_mossy", null);
            case "dirt" -> cubePart(id, "blocks/dirt", null);
            case "coarse_dirt" -> cubePartFallback(id, "blocks/coarse_dirt", "blocks/dirt", archive);
            case "bedrock" -> cubePart(id, "blocks/bedrock", null);
            case "sand" -> cubePart(id, "blocks/sand", null);
            case "red_sand" -> cubePart(id, "blocks/red_sand", null);
            case "gravel" -> cubePart(id, "blocks/gravel", null);
            case "coal_ore" -> cubePart(id, "blocks/coal_ore", null);
            case "iron_ore" -> cubePart(id, "blocks/iron_ore", null);
            case "gold_ore" -> cubePart(id, "blocks/gold_ore", null);
            case "diamond_ore" -> cubePart(id, "blocks/diamond_ore", null);
            case "emerald_ore" -> cubePart(id, "blocks/emerald_ore", null);
            case "lapis_ore" -> cubePart(id, "blocks/lapis_ore", null);
            case "redstone_ore" -> cubePart(id, "blocks/redstone_ore", null);
            case "nether_quartz_ore" -> cubePart(id, "blocks/quartz_ore", null);
            case "coal_block" -> cubePart(id, "blocks/coal_block", null);
            case "iron_block" -> cubePart(id, "blocks/iron_block", null);
            case "gold_block" -> cubePart(id, "blocks/gold_block", null);
            case "diamond_block" -> cubePart(id, "blocks/diamond_block", null);
            case "emerald_block" -> cubePart(id, "blocks/emerald_block", null);
            case "lapis_block" -> cubePart(id, "blocks/lapis_block", null);
            case "redstone_block" -> cubePart(id, "blocks/redstone_block", null);
            case "bricks" -> cubePart(id, "blocks/brick", null);
            case "nether_bricks" -> cubePart(id, "blocks/nether_brick", null);
            case "stone_bricks" -> cubePart(id, "blocks/stonebrick", null);
            case "mossy_stone_bricks" -> cubePart(id, "blocks/stonebrick_mossy", null);
            case "cracked_stone_bricks" -> cubePart(id, "blocks/stonebrick_cracked", null);
            case "chiseled_stone_bricks" -> cubePart(id, "blocks/stonebrick_carved", null);
            case "obsidian" -> cubePart(id, "blocks/obsidian", null);
            case "ice" -> cubePart(id, "blocks/ice", "minecraft:translucent");
            case "packed_ice" -> cubePart(id, "blocks/ice_packed", null);
            case "snow_block" -> cubePart(id, "blocks/snow", null);
            case "glowstone" -> cubePart(id, "blocks/glowstone", null);
            case "netherrack" -> cubePart(id, "blocks/netherrack", null);
            case "soul_sand" -> cubePart(id, "blocks/soul_sand", null);
            case "end_stone" -> cubePart(id, "blocks/end_stone", null);
            case "clay" -> cubePart(id, "blocks/clay", null);
            case "terracotta" -> cubePart(id, "blocks/hardened_clay", null);
            case "sponge" -> cubePart(id, "blocks/sponge", null);
            case "glass" -> cubePart(id, "blocks/glass", "minecraft:cutout");
            case "note_block" -> cubePart(id, "blocks/noteblock", null);
            case "spawner" -> cubePart(id, "blocks/mob_spawner", "minecraft:cutout");
            case "redstone_lamp" -> cubePart(id, "blocks/redstone_lamp_off", null);

            // Multi-face textured blocks
            case "grass_block" -> grassBlockPart(id, archive);
            case "podzol" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/dirt_podzol_top"), tex("blocks/dirt"), tex("blocks/dirt_podzol_side")), null));
            case "mycelium" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/mycelium_top"), tex("blocks/dirt"), tex("blocks/mycelium_side")), null));
            case "sandstone" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/sandstone_top"), tex("blocks/sandstone_bottom"), tex("blocks/sandstone_normal")), null));
            case "chiseled_sandstone" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/sandstone_top"), tex("blocks/sandstone_bottom"), tex("blocks/sandstone_carved")), null));
            case "cut_sandstone" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/sandstone_top"), tex("blocks/sandstone_bottom"), tex("blocks/sandstone_smooth")), null));
            case "quartz_block" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/quartz_block_top"), tex("blocks/quartz_block_bottom"), tex("blocks/quartz_block_side")), null));
            case "chiseled_quartz_block" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/quartz_block_chiseled_top"), tex("blocks/quartz_block_chiseled_top"), tex("blocks/quartz_block_chiseled")), null));
            case "quartz_pillar" -> {
                int xRot = (data == 3 || data == 4) ? 90 : 0;
                int yRot = data == 3 ? 90 : 0;
                yield List.of(new Part(id, xRot, yRot, false,
                        cubeColumn(tex("blocks/quartz_block_lines_top"), tex("blocks/quartz_block_lines_top"), tex("blocks/quartz_block_lines")), null));
            }
            case "crafting_table" -> List.of(new Part(id, 0, 0, false, cubeSix(
                    tex("blocks/planks_oak"), tex("blocks/crafting_table_top"),
                    tex("blocks/crafting_table_front"), tex("blocks/crafting_table_side"),
                    tex("blocks/crafting_table_front"), tex("blocks/crafting_table_side")), null));
            case "furnace" -> {
                int yRot = switch (data & 7) { case 3 -> 180; case 4 -> 270; case 5 -> 90; default -> 0; };
                yield List.of(new Part(id, 0, yRot, false, cubeSix(
                        tex("blocks/furnace_top"), tex("blocks/furnace_top"),
                        tex("blocks/furnace_front_off"), tex("blocks/furnace_side"),
                        tex("blocks/furnace_side"), tex("blocks/furnace_side")), null));
            }
            case "pumpkin", "jack_o_lantern" -> {
                int yRot = switch (data & 3) { case 0 -> 180; case 1 -> 270; case 3 -> 90; default -> 0; };
                ResourceLocation face = tex(name.equals("jack_o_lantern") ? "blocks/pumpkin_face_on" : "blocks/pumpkin_face_off");
                yield List.of(new Part(id, 0, yRot, false, cubeSix(
                        tex("blocks/pumpkin_top"), tex("blocks/pumpkin_top"),
                        face, tex("blocks/pumpkin_side"),
                        tex("blocks/pumpkin_side"), tex("blocks/pumpkin_side")), null));
            }
            case "melon" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/melon_top"), tex("blocks/melon_top"), tex("blocks/melon_side")), null));
            case "tnt" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/tnt_top"), tex("blocks/tnt_bottom"), tex("blocks/tnt_side")), null));
            case "bookshelf" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/planks_oak"), tex("blocks/planks_oak"), tex("blocks/bookshelf")), null));
            case "hay_block" -> {
                int axisBits = data & 0x0C;
                int xRot = (axisBits == 4 || axisBits == 8) ? 90 : 0;
                int yRot = axisBits == 4 ? 90 : 0;
                yield List.of(new Part(id, xRot, yRot, false,
                        cubeColumn(tex("blocks/hay_block_top"), tex("blocks/hay_block_top"), tex("blocks/hay_block_side")), null));
            }
            case "jukebox" -> List.of(new Part(id, 0, 0, false,
                    cubeColumn(tex("blocks/jukebox_top"), tex("blocks/jukebox_side"), tex("blocks/jukebox_side")), null));
            case "cactus" -> cactusPart(id);
            case "farmland" -> {
                ResourceLocation top = tex(data > 0 ? "blocks/farmland_wet" : "blocks/farmland_dry");
                ResourceLocation dirt = tex("blocks/dirt");
                List<Face> faces = new ArrayList<>(6);
                faces.add(face(Direction.DOWN, dirt, FULL_UV, -1, Direction.DOWN, 0, ZERO, new Vec3(16, 15, 16), true));
                faces.add(face(Direction.UP, top, FULL_UV, -1, null, 0, ZERO, new Vec3(16, 15, 16), true));
                for (Direction side : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
                    faces.add(face(side, dirt, new Uv(0, 1, 16, 16), -1, side, 0, ZERO, new Vec3(16, 15, 16), true));
                }
                yield List.of(new Part(id, 0, 0, false, faces, null));
            }
            case "snow" -> {
                int height = ((data & 7) + 1) * 2;
                yield boxPart(id, tex("blocks/snow"), 0, 0, 0, 16, height, 16, -1, null);
            }

            // Cross-plane vegetation
            case "short_grass" -> crossPart(id, "blocks/tallgrass", 0);
            case "fern" -> crossPart(id, "blocks/fern", 0);
            case "tall_grass" -> crossPart(id, (data & 8) != 0 ? "blocks/double_plant_grass_top" : "blocks/double_plant_grass_bottom", 0);
            case "large_fern" -> crossPart(id, (data & 8) != 0 ? "blocks/double_plant_fern_top" : "blocks/double_plant_fern_bottom", 0);
            case "sunflower" -> crossPart(id, (data & 8) != 0 ? "blocks/double_plant_sunflower_top" : "blocks/double_plant_sunflower_bottom", -1);
            case "lilac" -> crossPart(id, (data & 8) != 0 ? "blocks/double_plant_syringa_top" : "blocks/double_plant_syringa_bottom", -1);
            case "rose_bush" -> crossPart(id, (data & 8) != 0 ? "blocks/double_plant_rose_top" : "blocks/double_plant_rose_bottom", -1);
            case "peony" -> crossPart(id, (data & 8) != 0 ? "blocks/double_plant_paeonia_top" : "blocks/double_plant_paeonia_bottom", -1);
            case "dandelion" -> crossPart(id, "blocks/flower_dandelion", -1);
            case "poppy" -> crossPart(id, "blocks/flower_rose", -1);
            case "blue_orchid" -> crossPart(id, "blocks/flower_blue_orchid", -1);
            case "allium" -> crossPart(id, "blocks/flower_allium", -1);
            case "azure_bluet" -> crossPart(id, "blocks/flower_houstonia", -1);
            case "red_tulip" -> crossPart(id, "blocks/flower_tulip_red", -1);
            case "orange_tulip" -> crossPart(id, "blocks/flower_tulip_orange", -1);
            case "white_tulip" -> crossPart(id, "blocks/flower_tulip_white", -1);
            case "pink_tulip" -> crossPart(id, "blocks/flower_tulip_pink", -1);
            case "oxeye_daisy" -> crossPart(id, "blocks/flower_oxeye_daisy", -1);
            case "sugar_cane" -> crossPart(id, "blocks/reeds", 0);
            case "dead_bush" -> crossPart(id, "blocks/deadbush", -1);
            case "brown_mushroom" -> crossPart(id, "blocks/mushroom_brown", -1);
            case "red_mushroom" -> crossPart(id, "blocks/mushroom_red", -1);
            case "cobweb" -> crossPart(id, "blocks/web", -1);

            // Lily pad, vine, torch, ladder, rails
            case "lily_pad" -> lilyPadPart(id);
            case "vine" -> vinePart(id, data);
            case "torch" -> torchPart(id, "blocks/torch_on", data);
            case "redstone_torch" -> torchPart(id, "blocks/redstone_torch_on", data);
            case "ladder" -> ladderPart(id, data);
            case "rail" -> railPart(id, "blocks/rail_normal", "blocks/rail_normal_turned", data);
            case "powered_rail" -> railPart(id, (data & 8) != 0 ? "blocks/rail_golden_powered" : "blocks/rail_golden", null, data & 7);
            case "detector_rail" -> railPart(id, (data & 8) != 0 ? "blocks/rail_detector_powered" : "blocks/rail_detector", null, data & 7);
            case "activator_rail" -> railPart(id, (data & 8) != 0 ? "blocks/rail_activator_powered" : "blocks/rail_activator", null, data & 7);

            default -> null;
        };
    }

    private static List<Part> grassBlockPart(ResourceLocation id, BlockResourceSource archive) throws IOException {
        ResourceLocation top = tex("blocks/grass_top");
        ResourceLocation bottom = tex("blocks/dirt");
        ResourceLocation side = tex("blocks/grass_side");
        ResourceLocation overlay = tex("blocks/grass_side_overlay");
        boolean hasOverlay = archive.hasBaseTexture(overlay);
        List<Face> faces = new ArrayList<>(hasOverlay ? 10 : 6);
        faces.add(face(Direction.DOWN, bottom, FULL_UV, -1, Direction.DOWN, 0, ZERO, FULL, true));
        faces.add(face(Direction.UP, top, FULL_UV, 0, Direction.UP, 0, ZERO, FULL, true));
        for (Direction dir : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            faces.add(face(dir, side, FULL_UV, -1, dir, 0, ZERO, FULL, true));
            if (hasOverlay) {
                faces.add(face(dir, overlay, FULL_UV, 0, dir, 1, ZERO, FULL, true));
            }
        }
        return List.of(new Part(id, 0, 0, false, faces, "minecraft:cutout_mipped"));
    }

    private static List<Part> cactusPart(ResourceLocation id) {
        ResourceLocation top = tex("blocks/cactus_top");
        ResourceLocation bottom = tex("blocks/cactus_bottom");
        ResourceLocation side = tex("blocks/cactus_side");
        List<Face> faces = new ArrayList<>(6);
        faces.add(face(Direction.DOWN, bottom, FULL_UV, -1, Direction.DOWN, 0, ZERO, FULL, true));
        faces.add(face(Direction.UP, top, FULL_UV, -1, Direction.UP, 0, ZERO, FULL, true));
        Vec3 insetFrom = new Vec3(1, 0, 1);
        Vec3 insetTo = new Vec3(15, 16, 15);
        for (Direction dir : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            faces.add(face(dir, side, FULL_UV, -1, null, 1, insetFrom, insetTo, true));
        }
        return List.of(new Part(id, 0, 0, false, faces, "minecraft:cutout"));
    }

    private static List<Part> slabPart(ResourceLocation id, String name, int data) {
        ResourceLocation topTex;
        ResourceLocation sideTex;
        switch (name) {
            case "smooth_stone_slab" -> { topTex = tex("blocks/stone_slab_top"); sideTex = tex("blocks/stone_slab_side"); }
            case "sandstone_slab" -> { topTex = tex("blocks/sandstone_top"); sideTex = tex("blocks/sandstone_normal"); }
            case "cobblestone_slab" -> { topTex = tex("blocks/cobblestone"); sideTex = topTex; }
            case "brick_slab" -> { topTex = tex("blocks/brick"); sideTex = topTex; }
            case "stone_brick_slab" -> { topTex = tex("blocks/stonebrick"); sideTex = topTex; }
            case "nether_brick_slab" -> { topTex = tex("blocks/nether_brick"); sideTex = topTex; }
            case "quartz_slab" -> { topTex = tex("blocks/quartz_block_top"); sideTex = tex("blocks/quartz_block_side"); }
            case "oak_slab", "petrified_oak_slab" -> { topTex = tex("blocks/planks_oak"); sideTex = topTex; }
            case "spruce_slab" -> { topTex = tex("blocks/planks_spruce"); sideTex = topTex; }
            case "birch_slab" -> { topTex = tex("blocks/planks_birch"); sideTex = topTex; }
            case "jungle_slab" -> { topTex = tex("blocks/planks_jungle"); sideTex = topTex; }
            case "acacia_slab" -> { topTex = tex("blocks/planks_acacia"); sideTex = topTex; }
            case "dark_oak_slab" -> { topTex = tex("blocks/planks_big_oak"); sideTex = topTex; }
            default -> { return null; }
        }
        boolean top = (data & 8) != 0;
        double y0 = top ? 8 : 0;
        double y1 = top ? 16 : 8;
        Vec3 from = new Vec3(0, y0, 0);
        Vec3 to = new Vec3(16, y1, 16);
        Uv sideUv = top ? new Uv(0, 0, 16, 8) : new Uv(0, 8, 16, 16);
        List<Face> faces = new ArrayList<>(6);
        faces.add(face(Direction.DOWN, topTex, FULL_UV, -1, top ? null : Direction.DOWN, 0, from, to, true));
        faces.add(face(Direction.UP, topTex, FULL_UV, -1, top ? Direction.UP : null, 0, from, to, true));
        for (Direction dir : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            faces.add(face(dir, sideTex, sideUv, -1, dir, 0, from, to, true));
        }
        return List.of(new Part(id, 0, 0, false, faces, null));
    }

    private static List<Part> crossPart(ResourceLocation id, String texturePath, int tintIndex) {
        ResourceLocation texture = tex(texturePath);
        ElementRotation rotPos = new ElementRotation(new Vec3(8, 8, 8), "y", 45, true);
        ElementRotation rotNeg = new ElementRotation(new Vec3(8, 8, 8), "y", -45, true);
        Vec3 from = new Vec3(0.8, 0, 8);
        Vec3 to = new Vec3(15.2, 16, 8);
        List<Face> faces = List.of(
                new Face(Direction.NORTH, texture, FULL_UV, 0, tintIndex, null, 0, from, to, rotPos, false),
                new Face(Direction.SOUTH, texture, FULL_UV, 0, tintIndex, null, 0, from, to, rotPos, false),
                new Face(Direction.NORTH, texture, FULL_UV, 0, tintIndex, null, 1, from, to, rotNeg, false),
                new Face(Direction.SOUTH, texture, FULL_UV, 0, tintIndex, null, 1, from, to, rotNeg, false)
        );
        return List.of(new Part(id, 0, 0, false, faces, "minecraft:cutout"));
    }

    private static List<Part> lilyPadPart(ResourceLocation id) {
        ResourceLocation texture = tex("blocks/waterlily");
        Vec3 from = new Vec3(0, 0.25, 0);
        Vec3 to = new Vec3(16, 0.25, 16);
        List<Face> faces = List.of(
                new Face(Direction.UP, texture, FULL_UV, 0, 0, null, 0, from, to, null, false),
                new Face(Direction.DOWN, texture, new Uv(0, 16, 16, 0), 0, 0, null, 0, from, to, null, false)
        );
        return List.of(new Part(id, 0, 0, false, faces, "minecraft:cutout"));
    }

    private static List<Part> vinePart(ResourceLocation id, int data) {
        ResourceLocation texture = tex("blocks/vine");
        int mask = (data & 15) == 0 ? 1 : (data & 15);
        List<Face> faces = new ArrayList<>();
        int layer = 0;
        if ((mask & 1) != 0) { // south wall (z = 15.2)
            Vec3 f = new Vec3(0, 0, 15.2), t = new Vec3(16, 16, 15.2);
            faces.add(new Face(Direction.NORTH, texture, FULL_UV, 0, 0, null, layer, f, t, null, false));
            faces.add(new Face(Direction.SOUTH, texture, FULL_UV, 0, 0, null, layer++, f, t, null, false));
        }
        if ((mask & 2) != 0) { // west wall (x = 0.8)
            Vec3 f = new Vec3(0.8, 0, 0), t = new Vec3(0.8, 16, 16);
            faces.add(new Face(Direction.EAST, texture, FULL_UV, 0, 0, null, layer, f, t, null, false));
            faces.add(new Face(Direction.WEST, texture, FULL_UV, 0, 0, null, layer++, f, t, null, false));
        }
        if ((mask & 4) != 0) { // north wall (z = 0.8)
            Vec3 f = new Vec3(0, 0, 0.8), t = new Vec3(16, 16, 0.8);
            faces.add(new Face(Direction.SOUTH, texture, FULL_UV, 0, 0, null, layer, f, t, null, false));
            faces.add(new Face(Direction.NORTH, texture, FULL_UV, 0, 0, null, layer++, f, t, null, false));
        }
        if ((mask & 8) != 0) { // east wall (x = 15.2)
            Vec3 f = new Vec3(15.2, 0, 0), t = new Vec3(15.2, 16, 16);
            faces.add(new Face(Direction.WEST, texture, FULL_UV, 0, 0, null, layer, f, t, null, false));
            faces.add(new Face(Direction.EAST, texture, FULL_UV, 0, 0, null, layer++, f, t, null, false));
        }
        return List.of(new Part(id, 0, 0, false, faces, "minecraft:cutout"));
    }

    private static List<Part> torchPart(ResourceLocation id, String texturePath, int data) {
        ResourceLocation texture = tex(texturePath);
        Vec3 from = new Vec3(7, 0, 7);
        Vec3 to = new Vec3(9, 10, 9);
        List<Face> faces = List.of(
                new Face(Direction.UP, texture, new Uv(7, 6, 9, 8), 0, -1, null, 0, from, to, null, false),
                new Face(Direction.DOWN, texture, new Uv(7, 13, 9, 15), 0, -1, null, 0, from, to, null, false),
                new Face(Direction.NORTH, texture, new Uv(7, 6, 9, 16), 0, -1, null, 0, from, to, null, false),
                new Face(Direction.SOUTH, texture, new Uv(7, 6, 9, 16), 0, -1, null, 0, from, to, null, false),
                new Face(Direction.WEST, texture, new Uv(7, 6, 9, 16), 0, -1, null, 0, from, to, null, false),
                new Face(Direction.EAST, texture, new Uv(7, 6, 9, 16), 0, -1, null, 0, from, to, null, false)
        );
        return List.of(new Part(id, 0, 0, false, faces, "minecraft:cutout"));
    }

    private static List<Part> ladderPart(ResourceLocation id, int data) {
        ResourceLocation texture = tex("blocks/ladder");
        int yRot = switch (data & 7) { case 3 -> 180; case 4 -> 270; case 5 -> 90; default -> 0; };
        Vec3 from = new Vec3(0, 0, 15.2);
        Vec3 to = new Vec3(16, 16, 15.2);
        List<Face> faces = List.of(
                new Face(Direction.NORTH, texture, FULL_UV, 0, -1, null, 0, from, to, null, false),
                new Face(Direction.SOUTH, texture, FULL_UV, 0, -1, null, 0, from, to, null, false)
        );
        return List.of(new Part(id, 0, yRot, false, faces, "minecraft:cutout"));
    }

    private static List<Part> railPart(ResourceLocation id, String straightTex, String curvedTex, int shape) {
        boolean curved = curvedTex != null && shape >= 6 && shape <= 9;
        ResourceLocation texture = tex(curved ? curvedTex : straightTex);
        int yRot = switch (shape) {
            case 1, 2, 3 -> 90;
            case 7 -> 90;
            case 8 -> 180;
            case 9 -> 270;
            default -> 0;
        };
        Vec3 from = new Vec3(0, 1, 0);
        Vec3 to = new Vec3(16, 1, 16);
        List<Face> faces = List.of(
                new Face(Direction.UP, texture, FULL_UV, 0, -1, null, 0, from, to, null, false),
                new Face(Direction.DOWN, texture, new Uv(0, 16, 16, 0), 0, -1, null, 0, from, to, null, false)
        );
        return List.of(new Part(id, 0, yRot, false, faces, "minecraft:cutout"));
    }

    private static List<Part> cubePart(ResourceLocation id, String texturePath, String renderType) {
        return List.of(new Part(id, 0, 0, false, cubeAll(tex(texturePath), -1), renderType));
    }

    private static List<Part> cubePartFallback(ResourceLocation id, String primary, String fallback, BlockResourceSource archive) throws IOException {
        ResourceLocation first = tex(primary);
        ResourceLocation chosen = archive.hasBaseTexture(first) ? first : tex(fallback);
        return List.of(new Part(id, 0, 0, false, cubeAll(chosen, -1), null));
    }

    private static List<Part> boxPart(ResourceLocation id, ResourceLocation texture,
                                      double x0, double y0, double z0, double x1, double y1, double z1,
                                      int tintIndex, String renderType) {
        Vec3 from = new Vec3(x0, y0, z0);
        Vec3 to = new Vec3(x1, y1, z1);
        List<Face> faces = new ArrayList<>(6);
        for (Direction dir : Direction.values()) {
            faces.add(face(dir, texture, FULL_UV, tintIndex, null, 0, from, to, true));
        }
        return List.of(new Part(id, 0, 0, false, faces, renderType));
    }

    private static List<Face> cubeAll(ResourceLocation texture, int tintIndex) {
        List<Face> faces = new ArrayList<>(6);
        for (Direction dir : Direction.values()) {
            faces.add(face(dir, texture, FULL_UV, tintIndex, dir, 0, ZERO, FULL, true));
        }
        return faces;
    }

    private static List<Face> cubeColumn(ResourceLocation top, ResourceLocation bottom, ResourceLocation side) {
        return cubeSix(bottom, top, side, side, side, side);
    }

    private static List<Face> cubeSix(ResourceLocation down, ResourceLocation up,
                                      ResourceLocation north, ResourceLocation south,
                                      ResourceLocation west, ResourceLocation east) {
        return List.of(
                face(Direction.DOWN, down, FULL_UV, -1, Direction.DOWN, 0, ZERO, FULL, true),
                face(Direction.UP, up, FULL_UV, -1, Direction.UP, 0, ZERO, FULL, true),
                face(Direction.NORTH, north, FULL_UV, -1, Direction.NORTH, 0, ZERO, FULL, true),
                face(Direction.SOUTH, south, FULL_UV, -1, Direction.SOUTH, 0, ZERO, FULL, true),
                face(Direction.WEST, west, FULL_UV, -1, Direction.WEST, 0, ZERO, FULL, true),
                face(Direction.EAST, east, FULL_UV, -1, Direction.EAST, 0, ZERO, FULL, true)
        );
    }

    private static Face face(Direction dir, ResourceLocation texture, Uv uv, int tintIndex,
                             Direction cullFace, int layer, Vec3 from, Vec3 to, boolean shade) {
        return new Face(dir, texture, uv, 0, tintIndex, cullFace, layer, from, to, null, shade);
    }

    private static ResourceLocation tex(String path) {
        return new ResourceLocation("minecraft", path);
    }

    private static String woodStem(String wood) {
        return wood.equals("dark_oak") ? "big_oak" : wood;
    }

    private static String legacyColor(String color) {
        return color.equals("light_gray") ? "silver" : color;
    }

    private static int parseData(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw) & 0x0F;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void recordTextures(List<Part> parts, List<Source> sources) {
        for (Part part : parts) {
            for (Face face : part.faces()) {
                Source ref = new Source(ResourceLocation.Kind.TEXTURE, face.texture());
                if (!sources.contains(ref)) sources.add(ref);
            }
        }
    }
}
