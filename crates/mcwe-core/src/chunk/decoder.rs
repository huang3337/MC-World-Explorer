use super::{packed_indices::unpack, BlockState, ChunkSection};
use crate::{
    anvil::ChunkCoordinate,
    cancel::Cancellation,
    limits::{MAX_PALETTE, MAX_SECTIONS, MAX_TEXT_BYTES, MAX_TOTAL_TEXT_BYTES, NBT_OUTPUT_BYTES},
    nbt::decode_nbt,
    CoreError,
};
use serde::Deserialize;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone)]
pub struct DecodedChunk {
    pub coordinate: ChunkCoordinate,
    pub sections: BTreeMap<i8, ChunkSection>,
}
impl DecodedChunk {
    pub fn storage_budget_bytes(&self) -> usize {
        self.sections
            .values()
            .map(ChunkSection::storage_budget_bytes)
            .sum::<usize>()
            + self.sections.len() * 64
            + 128
    }

    pub fn block(&self, x: u8, world_y: i32, z: u8) -> Option<&BlockState> {
        let sy = world_y.div_euclid(16);
        let sy = i8::try_from(sy).ok()?;
        self.sections
            .get(&sy)?
            .block(x, world_y.rem_euclid(16) as u8, z)
    }
}

#[derive(Deserialize)]
struct Root {
    #[serde(rename = "xPos")]
    x: Option<i32>,
    #[serde(rename = "zPos")]
    z: Option<i32>,
    sections: Option<Vec<ModernSection>>,
    #[serde(rename = "Level")]
    level: Option<LegacyLevel>,
}
#[derive(Deserialize)]
struct LegacyLevel {
    #[serde(rename = "xPos")]
    x: Option<i32>,
    #[serde(rename = "zPos")]
    z: Option<i32>,
    #[serde(rename = "Sections")]
    sections: Option<Vec<LegacySection>>,
}
#[derive(Deserialize)]
struct ModernSection {
    #[serde(rename = "Y")]
    y: i8,
    block_states: Option<ModernStates>,
}
#[derive(Deserialize)]
struct ModernStates {
    palette: Vec<PaletteEntry>,
    data: Option<fastnbt::LongArray>,
}
#[derive(Deserialize)]
struct LegacySection {
    #[serde(rename = "Y")]
    y: i8,
    #[serde(rename = "Palette")]
    palette: Option<Vec<PaletteEntry>>,
    #[serde(rename = "BlockStates")]
    data: Option<fastnbt::LongArray>,
}
#[derive(Deserialize)]
struct PaletteEntry {
    #[serde(rename = "Name")]
    name: String,
    #[serde(rename = "Properties", default)]
    properties: BTreeMap<String, String>,
}

type RawSection = (i8, Option<Vec<PaletteEntry>>, Option<fastnbt::LongArray>);

pub fn decode_chunk(
    bytes: &[u8],
    expected: ChunkCoordinate,
    cancel: &dyn Cancellation,
) -> Result<DecodedChunk, CoreError> {
    cancel.check()?;
    let root: Root = decode_nbt(bytes, NBT_OUTPUT_BYTES, cancel)?;
    if root.sections.is_some()
        && root
            .level
            .as_ref()
            .and_then(|v| v.sections.as_ref())
            .is_some()
    {
        return Err(CoreError::InvalidChunk);
    }
    let (x, z, raw): (Option<i32>, Option<i32>, Vec<RawSection>) =
        if let Some(sections) = root.sections {
            (
                root.x,
                root.z,
                sections
                    .into_iter()
                    .map(|s| match s.block_states {
                        Some(states) => (s.y, Some(states.palette), states.data),
                        None => (s.y, None, None),
                    })
                    .collect(),
            )
        } else if let Some(level) = root.level {
            (
                level.x,
                level.z,
                level
                    .sections
                    .ok_or(CoreError::UnsupportedChunk)?
                    .into_iter()
                    .map(|s| (s.y, s.palette, s.data))
                    .collect(),
            )
        } else {
            return Err(CoreError::UnsupportedChunk);
        };
    if x != Some(expected.x) || z != Some(expected.z) {
        return Err(CoreError::InvalidChunk);
    }
    if raw.len() > MAX_SECTIONS {
        return Err(CoreError::ResourceLimit);
    }
    let mut seen = BTreeSet::new();
    let mut sections = BTreeMap::new();
    let mut text = 0usize;
    for (y, palette, data) in raw {
        cancel.check()?;
        if !seen.insert(y) {
            return Err(CoreError::InvalidChunk);
        }
        let Some(palette) = palette else { continue };
        if palette.is_empty() {
            return Err(CoreError::InvalidChunk);
        }
        if palette.len() > MAX_PALETTE {
            return Err(CoreError::ResourceLimit);
        }
        let mut states = Vec::with_capacity(palette.len());
        for p in palette {
            if p.name.len() > MAX_TEXT_BYTES {
                return Err(CoreError::ResourceLimit);
            }
            text = text
                .checked_add(p.name.len())
                .ok_or(CoreError::ResourceLimit)?;
            for (k, v) in &p.properties {
                if k.len() > MAX_TEXT_BYTES || v.len() > MAX_TEXT_BYTES {
                    return Err(CoreError::ResourceLimit);
                }
                text = text
                    .checked_add(k.len() + v.len())
                    .ok_or(CoreError::ResourceLimit)?;
            }
            if text > MAX_TOTAL_TEXT_BYTES {
                return Err(CoreError::ResourceLimit);
            }
            states.push(BlockState {
                name: p.name,
                properties: p.properties,
            });
        }
        let longs = data.map(fastnbt::LongArray::into_inner).unwrap_or_default();
        let indices = unpack(&longs, states.len())?;
        sections.insert(y, ChunkSection::new(y, states, indices));
    }
    if sections.is_empty() {
        return Err(CoreError::UnsupportedChunk);
    }
    Ok(DecodedChunk {
        coordinate: expected,
        sections,
    })
}
