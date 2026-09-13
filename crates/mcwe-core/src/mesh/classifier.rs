use crate::{chunk::BlockState, surface::block_color};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u32)]
pub enum RenderLayer {
    Opaque = 0,
    Cutout = 1,
    Translucent = 2,
    Fluid = 3,
    Fallback = 4,
}
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RenderClass {
    pub layer: Option<RenderLayer>,
    pub rgb: u32,
    pub alpha: f32,
}
pub fn classify(state: &BlockState) -> RenderClass {
    let n = state.name.as_str();
    if state.is_air() {
        return RenderClass {
            layer: None,
            rgb: 0,
            alpha: 0.0,
        };
    }
    if matches!(n, "minecraft:water" | "minecraft:lava") {
        return RenderClass {
            layer: Some(RenderLayer::Fluid),
            rgb: if n.ends_with("water") {
                0x3F76E4
            } else {
                0xF36B21
            },
            alpha: 0.72,
        };
    }
    let translucent = n.contains("glass")
        || matches!(
            n,
            "minecraft:ice"
                | "minecraft:frosted_ice"
                | "minecraft:slime_block"
                | "minecraft:honey_block"
                | "minecraft:nether_portal"
                | "minecraft:end_gateway"
                | "minecraft:end_portal"
        );
    if translucent {
        return RenderClass {
            layer: Some(RenderLayer::Translucent),
            rgb: block_color(n),
            alpha: 0.52,
        };
    }
    let cutout = n.contains("leaves")
        || matches!(
            n,
            "minecraft:short_grass"
                | "minecraft:tall_grass"
                | "minecraft:fern"
                | "minecraft:large_fern"
                | "minecraft:vine"
                | "minecraft:torch"
                | "minecraft:soul_torch"
                | "minecraft:redstone_torch"
                | "minecraft:rail"
                | "minecraft:powered_rail"
                | "minecraft:detector_rail"
                | "minecraft:activator_rail"
        );
    if cutout {
        return RenderClass {
            layer: Some(RenderLayer::Cutout),
            rgb: block_color(n),
            alpha: 1.0,
        };
    }
    if n.starts_with("minecraft:") {
        RenderClass {
            layer: Some(RenderLayer::Opaque),
            rgb: block_color(n),
            alpha: 1.0,
        }
    } else {
        RenderClass {
            layer: Some(RenderLayer::Fallback),
            rgb: 0xE83EED,
            alpha: 1.0,
        }
    }
}
