package com.mcworldexplorer.preview;

import java.awt.Color;
import java.util.Locale;

final class BlockColorPalette {
    private BlockColorPalette() {
    }

    static BlockColor resolve(String blockName) {
        String name = blockName.toLowerCase(Locale.ROOT);
        if (containsAny(name, "water", "bubble_column")) {
            return known(0x3F76E4);
        }
        if (containsAny(name, "lava", "magma")) {
            return known(0xF36B21);
        }
        if (containsAny(name, "snow", "powder_snow")) {
            return known(0xF3F6F7);
        }
        if (containsAny(name, "ice", "frosted_ice")) {
            return known(0x9DC3E6);
        }
        if (containsAny(name, "sand", "sandstone", "end_stone")) {
            return known(0xE5D18D);
        }
        if (containsAny(name, "grass", "moss", "fern", "vine")) {
            return known(0x6F9E43);
        }
        if (containsAny(name, "leaves", "azalea")) {
            return known(0x4E7F36);
        }
        if (containsAny(name, "dirt", "mud", "podzol", "farmland", "mycelium")) {
            return known(0x836044);
        }
        if (containsAny(name, "log", "wood", "stem", "hyphae", "planks")) {
            return known(0x8A6A42);
        }
        if (containsAny(name, "stone", "deepslate", "ore", "cobble", "gravel", "tuff", "basalt")) {
            return known(0x777A7C);
        }
        if (containsAny(name, "clay")) {
            return known(0x9BA6B2);
        }
        if (containsAny(name, "terracotta", "brick", "granite")) {
            return known(0xA45C44);
        }
        if (containsAny(name, "netherrack", "nether_wart")) {
            return known(0x772C2C);
        }
        if (containsAny(name, "black", "coal", "obsidian")) {
            return known(0x35383D);
        }
        if (containsAny(name, "white", "quartz", "calcite")) {
            return known(0xDDDAD2);
        }
        if (containsAny(name, "red")) {
            return known(0xB84A3E);
        }
        if (containsAny(name, "orange", "copper")) {
            return known(0xC9783D);
        }
        if (containsAny(name, "yellow", "gold")) {
            return known(0xD7B94A);
        }
        if (containsAny(name, "lime", "green")) {
            return known(0x609D4B);
        }
        if (containsAny(name, "cyan")) {
            return known(0x3E8D91);
        }
        if (containsAny(name, "light_blue", "blue")) {
            return known(0x496EA8);
        }
        if (containsAny(name, "purple", "magenta")) {
            return known(0x8C5A9E);
        }
        if (containsAny(name, "pink")) {
            return known(0xC7788D);
        }
        if (containsAny(name, "brown")) {
            return known(0x76543A);
        }
        if (containsAny(name, "gray", "iron")) {
            return known(0x858A8C);
        }

        int hash = blockName.hashCode();
        float hue = Math.floorMod(hash, 360) / 360.0f;
        float saturation = 0.28f + Math.floorMod(hash >>> 8, 18) / 100.0f;
        float brightness = 0.52f + Math.floorMod(hash >>> 16, 18) / 100.0f;
        return new BlockColor(Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF, false);
    }

    private static BlockColor known(int rgb) {
        return new BlockColor(rgb, true);
    }

    private static boolean containsAny(String name, String... fragments) {
        for (String fragment : fragments) {
            if (name.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    record BlockColor(int rgb, boolean known) {
    }
}
