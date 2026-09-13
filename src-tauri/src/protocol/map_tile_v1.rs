use super::header::{encode, i32_bytes, u32_bytes, Section};
use mcwe_core::{
    map::{MapTile, TILE_PIXELS},
    CoreError,
};

pub fn encode_map_tile_v1(
    tile: &MapTile,
    session_id: u64,
    task_id: u64,
) -> Result<Vec<u8>, CoreError> {
    let cells = TILE_PIXELS * TILE_PIXELS;
    if tile.colors.len() != cells
        || tile.heights.len() != cells
        || tile.statuses.len() != cells
        || tile.colors.iter().any(|color| *color > 0x00ff_ffff)
        || tile.statuses.iter().any(|status| *status > 3)
        || tile
            .statuses
            .iter()
            .zip(&tile.heights)
            .any(|(status, height)| *status != 0 && *height != 0)
    {
        return Err(CoreError::InvalidChunk);
    }
    let expected_origin = tile.request.bounds()?;
    if (tile.origin_block_x, tile.origin_block_z) != (expected_origin.0, expected_origin.1) {
        return Err(CoreError::InvalidChunk);
    }
    let total_chunks = tile
        .successful_chunks
        .checked_add(tile.missing_chunks)
        .and_then(|value| value.checked_add(tile.unsupported_chunks))
        .and_then(|value| value.checked_add(tile.corrupt_chunks))
        .ok_or(CoreError::ResourceLimit)?;
    let chunks_per_side = (TILE_PIXELS as u32)
        .checked_mul(u32::from(tile.request.blocks_per_pixel))
        .ok_or(CoreError::ResourceLimit)?
        / 16;
    if total_chunks != chunks_per_side * chunks_per_side {
        return Err(CoreError::InvalidChunk);
    }
    let metadata = format!(
        r#"{{"sessionId":{session_id},"taskId":{task_id},"tileX":{},"tileZ":{},"blocksPerPixel":{},"originBlockX":{},"originBlockZ":{},"successfulChunks":{},"missingChunks":{},"unsupportedChunks":{},"corruptChunks":{}}}"#,
        tile.request.tile_x,
        tile.request.tile_z,
        tile.request.blocks_per_pixel,
        tile.origin_block_x,
        tile.origin_block_z,
        tile.successful_chunks,
        tile.missing_chunks,
        tile.unsupported_chunks,
        tile.corrupt_chunks,
    );
    encode(
        3,
        vec![
            Section {
                id: 1,
                scalar: 4,
                count: cells as u32,
                bytes: u32_bytes(tile.colors.iter().copied())?,
            },
            Section {
                id: 2,
                scalar: 5,
                count: cells as u32,
                bytes: i32_bytes(tile.heights.iter().copied())?,
            },
            Section {
                id: 3,
                scalar: 1,
                count: cells as u32,
                bytes: tile.statuses.clone(),
            },
        ],
        &metadata,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use mcwe_core::map::MapTileRequest;

    fn tile() -> MapTile {
        MapTile {
            request: MapTileRequest {
                tile_x: -1,
                tile_z: 2,
                blocks_per_pixel: 1,
            },
            origin_block_x: -256,
            origin_block_z: 512,
            colors: vec![0; TILE_PIXELS * TILE_PIXELS],
            heights: vec![0; TILE_PIXELS * TILE_PIXELS],
            statuses: vec![1; TILE_PIXELS * TILE_PIXELS],
            successful_chunks: 0,
            missing_chunks: 256,
            unsupported_chunks: 0,
            corrupt_chunks: 0,
        }
    }

    #[test]
    fn emits_kind_three_with_exact_tile_identity() {
        let bytes = encode_map_tile_v1(&tile(), 7, 9).unwrap();
        assert_eq!(u16::from_le_bytes(bytes[6..8].try_into().unwrap()), 3);
        let metadata_offset = u32::from_le_bytes(bytes[16..20].try_into().unwrap()) as usize;
        let metadata = std::str::from_utf8(&bytes[metadata_offset..]).unwrap();
        assert!(metadata.contains(r#""tileX":-1,"tileZ":2,"blocksPerPixel":1"#));
    }

    #[test]
    fn rejects_inconsistent_counts_and_failed_height() {
        let mut value = tile();
        value.missing_chunks = 255;
        assert!(encode_map_tile_v1(&value, 1, 1).is_err());
        let mut value = tile();
        value.heights[0] = 1;
        assert!(encode_map_tile_v1(&value, 1, 1).is_err());
    }
}
