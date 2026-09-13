use super::PreviewBounds;
use crate::limits::PREVIEW_SIDE;
use crate::{
    anvil::{read_chunk_payload, ChunkCoordinate},
    cancel::Cancellation,
    chunk::decode_chunk,
    surface::{sample_chunk, SurfaceCell},
    world::WorldInfo,
    CoreError, WorldSource,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ChunkLoadStatus {
    Success = 0,
    Missing = 1,
    Unsupported = 2,
    Corrupt = 3,
    Cancelled = 4,
}

#[derive(Debug, Clone)]
pub struct SurfaceArea {
    pub origin_block_x: i32,
    pub origin_block_z: i32,
    pub center: ChunkCoordinate,
    pub cells: Vec<SurfaceCell>,
    pub statuses: Vec<ChunkLoadStatus>,
    pub successful_chunks: u16,
    pub failed_chunks: u16,
}

pub fn load_surface_area(
    source: &WorldSource,
    world: &WorldInfo,
    cancel: &dyn Cancellation,
) -> Result<SurfaceArea, CoreError> {
    let center = ChunkCoordinate {
        x: world.preview_center.position.x.div_euclid(16),
        z: world.preview_center.position.z.div_euclid(16),
    };
    cancel.check()?;
    let bounds = PreviewBounds::new(
        world.preview_center.position.x,
        world.preview_center.position.z,
    )?;
    let origin_block_x = bounds.min_x;
    let origin_block_z = bounds.min_z;
    let mut cells = vec![SurfaceCell::default(); PREVIEW_SIDE * PREVIEW_SIDE];
    let mut statuses = vec![ChunkLoadStatus::Missing; PREVIEW_SIDE * PREVIEW_SIDE];
    let mut successful = 0_u16;
    let mut failed = 0_u16;
    for cz in bounds.min_z.div_euclid(16)..=bounds.max_z.div_euclid(16) {
        for cx in bounds.min_x.div_euclid(16)..=bounds.max_x.div_euclid(16) {
            cancel.check()?;
            let c = ChunkCoordinate { x: cx, z: cz };
            let result = read_chunk_payload(source, c, cancel)
                .and_then(|p| p.ok_or(CoreError::NotFound))
                .and_then(|p| decode_chunk(&p.bytes, c, cancel));
            let (status, sampled) = match result {
                Ok(chunk) => (
                    ChunkLoadStatus::Success,
                    Some(sample_chunk(&chunk, cancel)?),
                ),
                Err(CoreError::NotFound) => (ChunkLoadStatus::Missing, None),
                Err(CoreError::UnsupportedChunk | CoreError::UnsupportedCompression) => {
                    (ChunkLoadStatus::Unsupported, None)
                }
                Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
                Err(_) => (ChunkLoadStatus::Corrupt, None),
            };
            if status == ChunkLoadStatus::Success {
                successful += 1;
            } else if matches!(
                status,
                ChunkLoadStatus::Unsupported | ChunkLoadStatus::Corrupt
            ) {
                failed += 1;
            }
            for z in 0..16 {
                for x in 0..16 {
                    let out_x = i64::from(cx) * 16 + x as i64 - i64::from(origin_block_x);
                    let out_z = i64::from(cz) * 16 + z as i64 - i64::from(origin_block_z);
                    if !(0..PREVIEW_SIDE as i64).contains(&out_x)
                        || !(0..PREVIEW_SIDE as i64).contains(&out_z)
                    {
                        continue;
                    }
                    let out = out_z as usize * PREVIEW_SIDE + out_x as usize;
                    statuses[out] = status;
                    if let Some(ref sample) = sampled {
                        cells[out] = sample[z * 16 + x];
                    }
                }
            }
        }
    }
    Ok(SurfaceArea {
        origin_block_x,
        origin_block_z,
        center,
        cells,
        statuses,
        successful_chunks: successful,
        failed_chunks: failed,
    })
}
