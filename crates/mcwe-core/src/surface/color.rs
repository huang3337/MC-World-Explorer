pub fn block_color(name: &str) -> u32 {
    let lower = name.to_lowercase();
    let groups: [(&[&str], u32); 25] = [
        (&["water", "bubble_column"], 0x3F76E4),
        (&["lava", "magma"], 0xF36B21),
        (&["snow", "powder_snow"], 0xF3F6F7),
        (&["ice", "frosted_ice"], 0x9DC3E6),
        (&["sand", "sandstone", "end_stone"], 0xE5D18D),
        (&["grass", "moss", "fern", "vine"], 0x6F9E43),
        (&["leaves", "azalea"], 0x4E7F36),
        (&["dirt", "mud", "podzol", "farmland", "mycelium"], 0x836044),
        (&["log", "wood", "stem", "hyphae", "planks"], 0x8A6A42),
        (
            &[
                "stone",
                "deepslate",
                "ore",
                "cobble",
                "gravel",
                "tuff",
                "basalt",
            ],
            0x777A7C,
        ),
        (&["clay"], 0x9BA6B2),
        (&["terracotta", "brick", "granite"], 0xA45C44),
        (&["netherrack", "nether_wart"], 0x772C2C),
        (&["black", "coal", "obsidian"], 0x35383D),
        (&["white", "quartz", "calcite"], 0xDDDAD2),
        (&["red"], 0xB84A3E),
        (&["orange", "copper"], 0xC9783D),
        (&["yellow", "gold"], 0xD7B94A),
        (&["lime", "green"], 0x609D4B),
        (&["cyan"], 0x3E8D91),
        (&["light_blue", "blue"], 0x496EA8),
        (&["purple", "magenta"], 0x8C5A9E),
        (&["pink"], 0xC7788D),
        (&["brown"], 0x76543A),
        (&["gray", "iron"], 0x858A8C),
    ];
    for (parts, rgb) in groups {
        if parts.iter().any(|p| lower.contains(p)) {
            return rgb;
        }
    }
    let mut hash = 0_i32;
    for c in name.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(i32::from(c));
    }
    let raw = hash as u32;
    let hue = hash.rem_euclid(360) as f32 / 360.0;
    let saturation = 0.28 + ((raw >> 8) % 18) as f32 / 100.0;
    let brightness = 0.52 + ((raw >> 16) % 18) as f32 / 100.0;
    hsb_to_rgb(hue, saturation, brightness)
}

fn hsb_to_rgb(hue: f32, saturation: f32, brightness: f32) -> u32 {
    let h = (hue - hue.floor()) * 6.0;
    let f = h - h.floor();
    let p = brightness * (1.0 - saturation);
    let q = brightness * (1.0 - saturation * f);
    let t = brightness * (1.0 - saturation * (1.0 - f));
    let (r, g, b) = match h as u32 {
        0 => (brightness, t, p),
        1 => (q, brightness, p),
        2 => (p, brightness, t),
        3 => (p, q, brightness),
        4 => (t, p, brightness),
        _ => (brightness, p, q),
    };
    (((r * 255.0 + 0.5) as u32) << 16)
        | (((g * 255.0 + 0.5) as u32) << 8)
        | ((b * 255.0 + 0.5) as u32)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn known_colors_match_java_baseline() {
        assert_eq!(block_color("minecraft:water"), 0x3F76E4);
        assert_eq!(block_color("minecraft:stone"), 0x777A7C);
    }
    #[test]
    fn fallback_is_stable() {
        assert_eq!(block_color("mod:block"), block_color("mod:block"));
    }
}
