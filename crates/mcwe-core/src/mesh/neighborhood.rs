use crate::{
    anvil::ChunkCoordinate,
    chunk::{BlockState, DecodedChunk},
    CoreError,
};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkRect {
    pub min_x: i32,
    pub min_z: i32,
    pub width: u8,
    pub depth: u8,
}
impl ChunkRect {
    pub fn validate(&self) -> Result<(), CoreError> {
        if !(1..=crate::limits::MAX_TARGET_SIDE).contains(&self.width)
            || !(1..=crate::limits::MAX_TARGET_SIDE).contains(&self.depth)
            || self.min_x.checked_add(i32::from(self.width)).is_none()
            || self.min_z.checked_add(i32::from(self.depth)).is_none()
        {
            Err(CoreError::InvalidChunk)
        } else {
            Ok(())
        }
    }

    pub fn validate_for_preview(&self, center_x: i32, center_z: i32) -> Result<(), CoreError> {
        self.validate()?;
        let bounds = crate::area::PreviewBounds::new(center_x, center_z)?;
        if !bounds.contains_chunks(self.min_x, self.min_z, self.width, self.depth) {
            return Err(CoreError::InvalidChunk);
        }
        Ok(())
    }

    pub fn neighborhood_coordinates(&self) -> Result<Vec<ChunkCoordinate>, CoreError> {
        self.validate()?;
        let start_x = self.min_x.checked_sub(1).ok_or(CoreError::InvalidChunk)?;
        let start_z = self.min_z.checked_sub(1).ok_or(CoreError::InvalidChunk)?;
        let end_x = self
            .min_x
            .checked_add(i32::from(self.width))
            .ok_or(CoreError::InvalidChunk)?;
        let end_z = self
            .min_z
            .checked_add(i32::from(self.depth))
            .ok_or(CoreError::InvalidChunk)?;
        let mut coordinates =
            Vec::with_capacity(usize::from(self.width + 2) * usize::from(self.depth + 2));
        for z in start_z..=end_z {
            for x in start_x..=end_x {
                coordinates.push(ChunkCoordinate { x, z });
            }
        }
        Ok(coordinates)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkFailure {
    Missing,
    Unsupported,
    Corrupt,
}

#[derive(Debug, Default)]
pub struct Neighborhood {
    pub chunks: BTreeMap<ChunkCoordinate, DecodedChunk>,
    pub failures: BTreeMap<ChunkCoordinate, ChunkFailure>,
}
impl Neighborhood {
    pub fn block(&self, world_x: i32, y: i32, world_z: i32) -> Option<&BlockState> {
        let c = ChunkCoordinate {
            x: world_x.div_euclid(16),
            z: world_z.div_euclid(16),
        };
        self.chunks.get(&c)?.block(
            world_x.rem_euclid(16) as u8,
            y,
            world_z.rem_euclid(16) as u8,
        )
    }
}
