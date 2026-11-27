package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pure static mapping from canonicalized pre-flattening block states (name + {@code data} 0..15)
 * to 1.8–1.12.2 {@code assets/minecraft/blockstates/*.json} resource IDs and variant properties.
 */
final class LegacyJsonStateMapper {
    record MappedState(ResourceLocation blockstateId, BlockState state) { }

    private LegacyJsonStateMapper() { }

    static MappedState map(BlockState original) {
        ResourceLocation id;
        try {
            id = ResourceLocation.parse(original.name());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (!id.namespace().equals("minecraft")) {
            return new MappedState(id, original);
        }
        int data = parseData(original.properties().get("data"));
        String path = id.path();
        Map<String, String> props = new TreeMap<>(original.properties());

        // Rename flattened light_gray -> 1.8-1.12.2 silver for dye-suffixed blocks.
        if (path.equals("light_gray_wool")) return simple("silver_wool", original, props);
        if (path.equals("light_gray_stained_glass")) return simple("silver_stained_glass", original, props);
        if (path.equals("light_gray_stained_glass_pane")) return pane("silver_stained_glass_pane", original, props);
        if (path.equals("light_gray_carpet")) return simple("silver_carpet", original, props);
        if (path.equals("light_gray_terracotta")) return simple("silver_stained_hardened_clay", original, props);
        if (path.equals("light_gray_concrete")) return simple("silver_concrete", original, props);
        if (path.equals("light_gray_concrete_powder")) return simple("silver_concrete_powder", original, props);
        if (path.equals("light_gray_shulker_box")) return simple("silver_shulker_box", original, props);
        if (path.equals("light_gray_glazed_terracotta")) {
            props.put("facing", horizontalFacing(data & 3));
            return mapped("silver_glazed_terracotta", original, props);
        }

        if (path.endsWith("_terracotta") && !path.endsWith("_glazed_terracotta")) {
            String color = path.substring(0, path.length() - "_terracotta".length());
            return simple(color + "_stained_hardened_clay", original, props);
        }
        if (path.endsWith("_glazed_terracotta")) {
            props.put("facing", horizontalFacing(data & 3));
            return mapped(path, original, props);
        }

        // Logs (axis from bits 2..3)
        if (path.endsWith("_log")) {
            props.put("axis", logAxis(data));
            return mapped(path, original, props);
        }
        // Leaves
        if (path.endsWith("_leaves")) {
            return simple(path, original, props);
        }
        // Saplings (stage from bit 3)
        if (path.endsWith("_sapling")) {
            props.put("stage", (data & 8) != 0 ? "1" : "0");
            return mapped(path, original, props);
        }
        // Stairs
        if (path.endsWith("_stairs")) {
            String legacyName = path.equals("cobblestone_stairs") ? "stone_stairs" : path;
            props.put("facing", stairsFacing(data & 3));
            props.put("half", (data & 4) != 0 ? "top" : "bottom");
            props.put("shape", "straight");
            return mapped(legacyName, original, props);
        }
        // Slabs
        if (path.endsWith("_slab")) {
            String legacyName = switch (path) {
                case "smooth_stone_slab" -> "stone_slab";
                case "petrified_oak_slab" -> "oak_slab";
                default -> path;
            };
            props.put("half", (data & 8) != 0 ? "top" : "bottom");
            return mapped(legacyName, original, props);
        }
        // Doors
        if (path.endsWith("_door")) {
            String legacyName = path.equals("oak_door") ? "wooden_door" : path;
            if ((data & 8) != 0) {
                props.put("half", "upper");
                props.put("hinge", (data & 1) != 0 ? "right" : "left");
                props.put("facing", "east");
                props.put("open", "false");
            } else {
                props.put("half", "lower");
                props.put("facing", doorFacing(data & 3));
                props.put("open", (data & 4) != 0 ? "true" : "false");
                props.put("hinge", "left");
            }
            return mapped(legacyName, original, props);
        }
        // Trapdoors
        if (path.equals("oak_trapdoor") || path.equals("iron_trapdoor")) {
            String legacyName = path.equals("oak_trapdoor") ? "trapdoor" : path;
            props.put("facing", trapdoorFacing(data & 3));
            props.put("open", (data & 4) != 0 ? "true" : "false");
            props.put("half", (data & 8) != 0 ? "top" : "bottom");
            return mapped(legacyName, original, props);
        }
        // Fences
        if (path.endsWith("_fence")) {
            String legacyName = path.equals("oak_fence") ? "fence" : path;
            return pane(legacyName, original, props);
        }
        // Fence gates
        if (path.endsWith("_fence_gate")) {
            String legacyName = path.equals("oak_fence_gate") ? "fence_gate" : path;
            props.put("facing", horizontalFacing(data & 3));
            props.put("open", (data & 4) != 0 ? "true" : "false");
            props.put("in_wall", "false");
            return mapped(legacyName, original, props);
        }
        // Glass panes & iron bars
        if (path.endsWith("_stained_glass_pane") || path.equals("glass_pane") || path.equals("iron_bars")) {
            return pane(path, original, props);
        }

        return switch (path) {
            case "grass_block" -> {
                props.put("snowy", "false");
                yield mapped("grass", original, props);
            }
            case "podzol", "mycelium" -> {
                props.put("snowy", "false");
                yield mapped(path, original, props);
            }
            case "polished_granite" -> simple("smooth_granite", original, props);
            case "polished_diorite" -> simple("smooth_diorite", original, props);
            case "polished_andesite" -> simple("smooth_andesite", original, props);
            case "cut_sandstone" -> simple("smooth_sandstone", original, props);
            case "cut_red_sandstone" -> simple("smooth_red_sandstone", original, props);
            case "sponge" -> {
                props.put("wet", "false");
                yield mapped("sponge", original, props);
            }
            case "wet_sponge" -> {
                props.put("wet", "true");
                yield mapped("sponge", original, props);
            }
            case "tnt" -> {
                props.put("explode", (data & 1) != 0 ? "true" : "false");
                yield mapped("tnt", original, props);
            }
            case "azure_bluet" -> simple("houstonia", original, props);
            case "tall_grass" -> doublePlant("double_grass", data, original, props);
            case "large_fern" -> doublePlant("double_fern", data, original, props);
            case "sunflower" -> doublePlant("sunflower", data, original, props);
            case "lilac" -> doublePlant("syringa", data, original, props);
            case "rose_bush" -> doublePlant("double_rose", data, original, props);
            case "peony" -> doublePlant("paeonia", data, original, props);
            case "terracotta" -> simple("hardened_clay", original, props);
            case "bricks" -> simple("brick_block", original, props);
            case "nether_bricks" -> simple("nether_brick", original, props);
            case "stone_bricks" -> simple("stonebrick", original, props);
            case "mossy_stone_bricks" -> simple("mossy_stonebrick", original, props);
            case "cracked_stone_bricks" -> simple("cracked_stonebrick", original, props);
            case "chiseled_stone_bricks" -> simple("chiseled_stonebrick", original, props);
            case "short_grass" -> simple("tall_grass", original, props);
            case "lily_pad" -> simple("waterlily", original, props);
            case "sugar_cane" -> simple("reeds", original, props);
            case "cobweb" -> simple("web", original, props);
            case "snow_block" -> simple("snow", original, props);
            case "snow" -> {
                props.put("layers", Integer.toString((data & 7) + 1));
                yield mapped("snow_layer", original, props);
            }
            case "dirt_path" -> simple("grass_path", original, props);
            case "melon" -> simple("melon_block", original, props);
            case "note_block" -> simple("noteblock", original, props);
            case "spawner" -> simple("mob_spawner", original, props);
            case "nether_quartz_ore" -> simple("quartz_ore", original, props);
            case "slime_block" -> simple("slime", original, props);
            case "end_stone_bricks" -> simple("end_bricks", original, props);
            case "magma_block" -> simple("magma", original, props);
            case "red_nether_bricks" -> simple("red_nether_brick", original, props);
            case "pumpkin" -> {
                props.put("facing", horizontalFacing(data & 3));
                yield mapped("pumpkin", original, props);
            }
            case "jack_o_lantern" -> {
                props.put("facing", horizontalFacing(data & 3));
                yield mapped("lit_pumpkin", original, props);
            }
            case "furnace", "ladder" -> {
                props.put("facing", wallFacing(data & 7));
                yield mapped(path, original, props);
            }
            case "dispenser", "dropper" -> {
                props.put("facing", sixWayFacing(data & 7));
                yield mapped(path, original, props);
            }
            case "piston", "sticky_piston" -> {
                props.put("facing", sixWayFacing(data & 7));
                props.put("extended", (data & 8) != 0 ? "true" : "false");
                yield mapped(path, original, props);
            }
            case "torch", "redstone_torch" -> {
                props.put("facing", torchFacing(data & 7));
                yield mapped(path, original, props);
            }
            case "nether_portal" -> {
                props.put("axis", (data & 3) == 2 ? "z" : "x");
                yield mapped("portal", original, props);
            }
            case "hay_block", "bone_block" -> {
                props.put("axis", pillarAxis(data));
                yield mapped(path, original, props);
            }
            case "quartz_pillar" -> {
                props.put("axis", data == 3 ? "x" : data == 4 ? "z" : "y");
                yield mapped("quartz_column", original, props);
            }
            case "vine" -> {
                props.put("south", (data & 1) != 0 ? "true" : "false");
                props.put("west", (data & 2) != 0 ? "true" : "false");
                props.put("north", (data & 4) != 0 ? "true" : "false");
                props.put("east", (data & 8) != 0 ? "true" : "false");
                props.put("up", "false");
                yield mapped("vine", original, props);
            }
            case "redstone_wire" -> {
                props.put("north", "none");
                props.put("east", "none");
                props.put("south", "none");
                props.put("west", "none");
                props.put("power", Integer.toString(data & 15));
                yield mapped("redstone_wire", original, props);
            }
            case "rail" -> {
                props.put("shape", railShape(data & 15));
                yield mapped("rail", original, props);
            }
            case "powered_rail", "detector_rail", "activator_rail" -> {
                String legacyName = path.equals("powered_rail") ? "golden_rail" : path;
                props.put("powered", (data & 8) != 0 ? "true" : "false");
                props.put("shape", railShape(data & 7));
                yield mapped(legacyName, original, props);
            }
            case "lever" -> {
                props.put("powered", (data & 8) != 0 ? "true" : "false");
                props.put("facing", leverFacing(data & 7));
                yield mapped("lever", original, props);
            }
            case "oak_button", "stone_button" -> {
                String legacyName = path.equals("oak_button") ? "wooden_button" : path;
                props.put("powered", (data & 8) != 0 ? "true" : "false");
                props.put("facing", buttonFacing(data & 7));
                yield mapped(legacyName, original, props);
            }
            case "oak_pressure_plate", "stone_pressure_plate" -> {
                String legacyName = path.equals("oak_pressure_plate") ? "wooden_pressure_plate" : path;
                props.put("powered", (data & 1) != 0 ? "true" : "false");
                yield mapped(legacyName, original, props);
            }
            case "cobblestone_wall" -> {
                props.put("up", "true");
                yield pane("cobblestone_wall", original, props);
            }
            case "farmland" -> {
                props.put("moisture", Integer.toString(data & 7));
                yield mapped("farmland", original, props);
            }
            case "wheat", "carrots", "potatoes" -> {
                props.put("age", Integer.toString(data & 7));
                yield mapped(path, original, props);
            }
            case "nether_wart", "beetroots" -> {
                props.put("age", Integer.toString(data & 3));
                yield mapped(path, original, props);
            }
            case "repeater" -> {
                props.put("facing", horizontalFacing(data & 3));
                props.put("delay", Integer.toString(((data >> 2) & 3) + 1));
                props.put("locked", "false");
                yield mapped("unpowered_repeater", original, props);
            }
            case "comparator" -> {
                props.put("facing", horizontalFacing(data & 3));
                props.put("mode", (data & 4) != 0 ? "subtract" : "compare");
                props.put("powered", (data & 8) != 0 ? "true" : "false");
                yield mapped("unpowered_comparator", original, props);
            }
            default -> new MappedState(id, original);
        };
    }

    private static MappedState simple(String legacyName, BlockState original, Map<String, String> props) {
        return new MappedState(new ResourceLocation("minecraft", legacyName), original.withProperties(props));
    }

    private static MappedState mapped(String legacyName, BlockState original, Map<String, String> props) {
        return new MappedState(new ResourceLocation("minecraft", legacyName), original.withProperties(props));
    }

    private static MappedState doublePlant(String legacyName, int data, BlockState original, Map<String, String> props) {
        props.put("half", (data & 8) != 0 ? "upper" : "lower");
        return new MappedState(new ResourceLocation("minecraft", legacyName), original.withProperties(props));
    }

    private static MappedState pane(String legacyName, BlockState original, Map<String, String> props) {
        props.put("north", "false");
        props.put("east", "false");
        props.put("south", "false");
        props.put("west", "false");
        return new MappedState(new ResourceLocation("minecraft", legacyName), original.withProperties(props));
    }

    private static int parseData(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw) & 0x0F;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String logAxis(int data) {
        return switch (data & 0x0C) {
            case 4 -> "x";
            case 8 -> "z";
            case 12 -> "none";
            default -> "y";
        };
    }

    private static String pillarAxis(int data) {
        return switch (data & 0x0C) {
            case 4 -> "x";
            case 8 -> "z";
            default -> "y";
        };
    }

    private static String horizontalFacing(int index) {
        return switch (index & 3) {
            case 0 -> "south";
            case 1 -> "west";
            case 2 -> "north";
            default -> "east";
        };
    }

    private static String stairsFacing(int index) {
        return switch (index & 3) {
            case 0 -> "east";
            case 1 -> "west";
            case 2 -> "south";
            default -> "north";
        };
    }

    private static String doorFacing(int index) {
        return switch (index & 3) {
            case 0 -> "east";
            case 1 -> "south";
            case 2 -> "west";
            default -> "north";
        };
    }

    private static String trapdoorFacing(int index) {
        return switch (index & 3) {
            case 0 -> "north";
            case 1 -> "south";
            case 2 -> "west";
            default -> "east";
        };
    }

    private static String wallFacing(int index) {
        return switch (index) {
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "north";
        };
    }

    private static String sixWayFacing(int index) {
        return switch (index) {
            case 0 -> "down";
            case 1 -> "up";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "north";
        };
    }

    private static String torchFacing(int index) {
        return switch (index) {
            case 1 -> "east";
            case 2 -> "west";
            case 3 -> "south";
            case 4 -> "north";
            default -> "up";
        };
    }

    private static String buttonFacing(int index) {
        return switch (index) {
            case 0 -> "down";
            case 1 -> "east";
            case 2 -> "west";
            case 3 -> "south";
            case 4 -> "north";
            default -> "up";
        };
    }

    private static String leverFacing(int index) {
        return switch (index) {
            case 0 -> "down_x";
            case 1 -> "east";
            case 2 -> "west";
            case 3 -> "south";
            case 4 -> "north";
            case 5 -> "up_z";
            case 6 -> "up_x";
            case 7 -> "down_z";
            default -> "up_z";
        };
    }

    private static String railShape(int index) {
        return switch (index) {
            case 1 -> "east_west";
            case 2 -> "ascending_east";
            case 3 -> "ascending_west";
            case 4 -> "ascending_north";
            case 5 -> "ascending_south";
            case 6 -> "south_east";
            case 7 -> "south_west";
            case 8 -> "north_west";
            case 9 -> "north_east";
            default -> "north_south";
        };
    }
}
