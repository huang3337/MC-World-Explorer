use crate::{
    anvil::{ChunkCoordinate, RegionReader},
    cancel::Cancellation,
    chunk::decode_chunk,
    surface::sample_chunk,
    CoreError, WorldSource,
};

pub const TILE_PIXELS: usize = 256;
const TILE_CELLS: usize = TILE_PIXELS * TILE_PIXELS;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MapTileRequest {
    pub tile_x: i32,
    pub tile_z: i32,
    pub blocks_per_pixel: u8,
}

impl MapTileRequest {
    pub fn bounds(self) -> Result<(i32, i32, i32, i32), CoreError> {
        if !matches!(self.blocks_per_pixel, 1 | 2 | 4 | 8 | 16) {
            return Err(CoreError::InvalidPath);
        }
        let size = i32::try_from(TILE_PIXELS)
            .ok()
            .and_then(|pixels| pixels.checked_mul(i32::from(self.blocks_per_pixel)))
            .ok_or(CoreError::ResourceLimit)?;
        let min_x = self
            .tile_x
            .checked_mul(size)
            .ok_or(CoreError::ResourceLimit)?;
        let min_z = self
            .tile_z
            .checked_mul(size)
            .ok_or(CoreError::ResourceLimit)?;
        let max_x = min_x.checked_add(size).ok_or(CoreError::ResourceLimit)?;
        let max_z = min_z.checked_add(size).ok_or(CoreError::ResourceLimit)?;
        Ok((min_x, min_z, max_x, max_z))
    }
}

#[derive(Debug, Clone)]
pub struct MapTile {
    pub request: MapTileRequest,
    pub origin_block_x: i32,
    pub origin_block_z: i32,
    pub colors: Vec<u32>,
    pub heights: Vec<i32>,
    pub statuses: Vec<u8>,
    pub successful_chunks: u32,
    pub missing_chunks: u32,
    pub unsupported_chunks: u32,
    pub corrupt_chunks: u32,
}

pub fn load_map_tile(
    source: &WorldSource,
    request: MapTileRequest,
    cancel: &dyn Cancellation,
) -> Result<MapTile, CoreError> {
    let (min_x, min_z, max_x, max_z) = request.bounds()?;
    let min_chunk_x = min_x.div_euclid(16);
    let min_chunk_z = min_z.div_euclid(16);
    let max_chunk_x = (max_x - 1).div_euclid(16);
    let max_chunk_z = (max_z - 1).div_euclid(16);
    let mut red = vec![0_u64; TILE_CELLS];
    let mut green = vec![0_u64; TILE_CELLS];
    let mut blue = vec![0_u64; TILE_CELLS];
    let mut height = vec![0_i64; TILE_CELLS];
    let mut counts = vec![0_u16; TILE_CELLS];
    let mut statuses = vec![1_u8; TILE_CELLS];
    let mut chunk_counts = [0_u32; 4];

    for region_z in min_chunk_z.div_euclid(32)..=max_chunk_z.div_euclid(32) {
        for region_x in min_chunk_x.div_euclid(32)..=max_chunk_x.div_euclid(32) {
            cancel.check()?;
            let mut reader = match RegionReader::open(source, region_x, region_z) {
                Ok(value) => value,
                Err(_) => {
                    mark_region_status(&mut statuses, request, min_x, min_z, region_x, region_z, 3);
                    let chunks_x =
                        overlap_count(min_chunk_x, max_chunk_x, region_x * 32, region_x * 32 + 31);
                    let chunks_z =
                        overlap_count(min_chunk_z, max_chunk_z, region_z * 32, region_z * 32 + 31);
                    chunk_counts[3] += chunks_x * chunks_z;
                    continue;
                }
            };
            let start_z = min_chunk_z.max(region_z * 32);
            let end_z = max_chunk_z.min(region_z * 32 + 31);
            let start_x = min_chunk_x.max(region_x * 32);
            let end_x = max_chunk_x.min(region_x * 32 + 31);
            for chunk_z in start_z..=end_z {
                for chunk_x in start_x..=end_x {
                    cancel.check()?;
                    let coordinate = ChunkCoordinate {
                        x: chunk_x,
                        z: chunk_z,
                    };
                    let result = match reader.as_mut() {
                        None => Ok(None),
                        Some(reader) => reader.read_chunk_payload(coordinate, cancel),
                    };
                    let (status, sampled) = match result {
                        Ok(Some(payload)) => match decode_chunk(&payload.bytes, coordinate, cancel)
                        {
                            Ok(chunk) => (0, Some(sample_chunk(&chunk, cancel)?)),
                            Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
                            Err(
                                CoreError::UnsupportedChunk | CoreError::UnsupportedCompression,
                            ) => (2, None),
                            Err(_) => (3, None),
                        },
                        Ok(None) | Err(CoreError::NotFound) => (1, None),
                        Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
                        Err(CoreError::UnsupportedChunk | CoreError::UnsupportedCompression) => {
                            (2, None)
                        }
                        Err(_) => (3, None),
                    };
                    chunk_counts[status as usize] += 1;
                    accept_chunk(
                        request,
                        min_x,
                        min_z,
                        coordinate,
                        status,
                        sampled.as_deref(),
                        &mut statuses,
                        &mut red,
                        &mut green,
                        &mut blue,
                        &mut height,
                        &mut counts,
                    );
                }
            }
        }
    }

    let mut colors = vec![0_u32; TILE_CELLS];
    let mut heights = vec![0_i32; TILE_CELLS];
    for index in 0..TILE_CELLS {
        let count = u64::from(counts[index]);
        if count > 0 {
            colors[index] = (red[index].checked_div(count).unwrap_or(0) as u32) << 16
                | (green[index].checked_div(count).unwrap_or(0) as u32) << 8
                | blue[index].checked_div(count).unwrap_or(0) as u32;
            heights[index] = height[index].checked_div_euclid(count as i64).unwrap_or(0) as i32;
            statuses[index] = 0;
        }
    }
    shade(&mut colors, &heights, &statuses);
    Ok(MapTile {
        request,
        origin_block_x: min_x,
        origin_block_z: min_z,
        colors,
        heights,
        statuses,
        successful_chunks: chunk_counts[0],
        missing_chunks: chunk_counts[1],
        unsupported_chunks: chunk_counts[2],
        corrupt_chunks: chunk_counts[3],
    })
}

fn overlap_count(minimum: i32, maximum: i32, range_min: i32, range_max: i32) -> u32 {
    u32::try_from((maximum.min(range_max) - minimum.max(range_min) + 1).max(0)).unwrap_or(0)
}

fn mark_region_status(
    statuses: &mut [u8],
    request: MapTileRequest,
    min_x: i32,
    min_z: i32,
    region_x: i32,
    region_z: i32,
    status: u8,
) {
    let bpp = i64::from(request.blocks_per_pixel);
    let region_min_x = i64::from(region_x) * 512;
    let region_min_z = i64::from(region_z) * 512;
    let start_x = ((region_min_x - i64::from(min_x)).max(0) / bpp) as i32;
    let start_z = ((region_min_z - i64::from(min_z)).max(0) / bpp) as i32;
    let end_x = ((region_min_x + 512 - i64::from(min_x)).min(bpp * 256) / bpp) as i32;
    let end_z = ((region_min_z + 512 - i64::from(min_z)).min(bpp * 256) / bpp) as i32;
    for z in start_z..end_z {
        statuses[z as usize * TILE_PIXELS + start_x as usize
            ..z as usize * TILE_PIXELS + end_x as usize]
            .fill(status);
    }
}

#[allow(clippy::too_many_arguments)]
fn accept_chunk(
    request: MapTileRequest,
    min_x: i32,
    min_z: i32,
    coordinate: ChunkCoordinate,
    status: u8,
    sampled: Option<&[crate::surface::SurfaceCell]>,
    statuses: &mut [u8],
    red: &mut [u64],
    green: &mut [u64],
    blue: &mut [u64],
    heights: &mut [i64],
    counts: &mut [u16],
) {
    for local_z in 0..16 {
        for local_x in 0..16 {
            let world_x = coordinate.x * 16 + local_x as i32;
            let world_z = coordinate.z * 16 + local_z as i32;
            let pixel_x = (world_x - min_x) / i32::from(request.blocks_per_pixel);
            let pixel_z = (world_z - min_z) / i32::from(request.blocks_per_pixel);
            let index = pixel_z as usize * TILE_PIXELS + pixel_x as usize;
            statuses[index] = status;
            if let Some(sampled) = sampled {
                let cell = sampled[local_z * 16 + local_x];
                red[index] += u64::from((cell.color >> 16) & 0xff);
                green[index] += u64::from((cell.color >> 8) & 0xff);
                blue[index] += u64::from(cell.color & 0xff);
                heights[index] += i64::from(cell.height);
                counts[index] += 1;
            }
        }
    }
}

fn shade(colors: &mut [u32], heights: &[i32], statuses: &[u8]) {
    let base = colors.to_vec();
    for z in 0..TILE_PIXELS {
        for x in 0..TILE_PIXELS {
            let index = z * TILE_PIXELS + x;
            if statuses[index] != 0 {
                continue;
            }
            let mut sum = 0_i64;
            let mut count = 0_i64;
            if x > 0 && statuses[index - 1] == 0 {
                sum += i64::from(heights[index - 1]);
                count += 1;
            }
            if z > 0 && statuses[index - TILE_PIXELS] == 0 {
                sum += i64::from(heights[index - TILE_PIXELS]);
                count += 1;
            }
            let difference = if count == 0 {
                0
            } else {
                i64::from(heights[index]) - sum.div_euclid(count)
            };
            let factor = (1.0 + difference as f64 * 0.035).clamp(0.78, 1.22);
            let channel = |shift: u32| {
                (((base[index] >> shift) & 0xff_u32) as f64 * factor)
                    .round()
                    .clamp(0.0, 255.0) as u32
            };
            colors[index] = channel(16) << 16 | channel(8) << 8 | channel(0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tile_bounds_match_java_for_negative_coordinates_and_all_levels() {
        for blocks_per_pixel in [1, 2, 4, 8, 16] {
            let request = MapTileRequest {
                tile_x: -1,
                tile_z: 2,
                blocks_per_pixel,
            };
            let size = 256 * i32::from(blocks_per_pixel);
            assert_eq!(request.bounds().unwrap(), (-size, size * 2, 0, size * 3));
        }
    }

    #[test]
    fn tile_bounds_reject_levels_and_overflow() {
        assert!(MapTileRequest {
            tile_x: 0,
            tile_z: 0,
            blocks_per_pixel: 3
        }
        .bounds()
        .is_err());
        assert!(MapTileRequest {
            tile_x: i32::MAX,
            tile_z: 0,
            blocks_per_pixel: 16
        }
        .bounds()
        .is_err());
    }
}
