use super::{
    header::{encode, i32_bytes, u32_bytes, Section},
    MAX_SAFE_INTEGER_ID,
};
use crate::tasks::{MapFailureKind, MapResultItem, MapResultPayload};
use mcwe_core::{map::TILE_PIXELS, CoreError};
use std::collections::HashSet;

pub const MAP_BATCH_MAX_ITEMS: usize = 16;
pub const MAP_BATCH_MAX_BYTES: usize = 8 * 1024 * 1024;
const DESCRIPTOR_WORDS: usize = 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapBatchState {
    Items,
    Timeout,
    Ended,
    Cancelled,
    ShuttingDown,
}

impl MapBatchState {
    fn as_str(self) -> &'static str {
        match self {
            Self::Items => "items",
            Self::Timeout => "timeout",
            Self::Ended => "ended",
            Self::Cancelled => "cancelled",
            Self::ShuttingDown => "shuttingDown",
        }
    }
}

pub fn encode_map_batch_v1(
    session_id: u64,
    viewport_task_id: u64,
    batch_sequence: u64,
    items: &[MapResultItem],
    has_more: bool,
    state: MapBatchState,
) -> Result<Vec<u8>, CoreError> {
    if session_id == 0
        || session_id > MAX_SAFE_INTEGER_ID
        || viewport_task_id == 0
        || viewport_task_id > MAX_SAFE_INTEGER_ID
        || batch_sequence == 0
        || batch_sequence > MAX_SAFE_INTEGER_ID
        || items.len() > MAP_BATCH_MAX_ITEMS
        || (items.is_empty() && state == MapBatchState::Items)
        || (!items.is_empty() && !matches!(state, MapBatchState::Items | MapBatchState::Ended))
        || (has_more && state != MapBatchState::Items)
    {
        return Err(CoreError::InvalidChunk);
    }

    let mut descriptors = Vec::with_capacity(items.len() * DESCRIPTOR_WORDS);
    let mut colors = Vec::new();
    let mut heights = Vec::new();
    let mut statuses = Vec::new();
    let mut identities = HashSet::with_capacity(items.len());
    let mut terminal_item_count = 0_usize;

    for item in items {
        if item.identity.session_id != session_id
            || item.identity.viewport_id != viewport_task_id
            || item.identity.key.session_id() != session_id
            || !identities.insert(item.identity.key)
        {
            return Err(CoreError::InvalidChunk);
        }
        let key = item.identity.key;
        let mut descriptor = [0_u32; DESCRIPTOR_WORDS];
        descriptor[1] = key.tile_x() as u32;
        descriptor[2] = key.tile_z() as u32;
        descriptor[3] = u32::from(key.blocks_per_pixel());

        match &item.payload {
            MapResultPayload::Partial(patch) => {
                let count = validate_pixels(&patch.colors, &patch.heights, &patch.statuses, true)?;
                let total_chunks = tile_chunk_count(key.blocks_per_pixel())?;
                if patch.request.tile_x != key.tile_x()
                    || patch.request.tile_z != key.tile_z()
                    || patch.request.blocks_per_pixel != key.blocks_per_pixel()
                    || patch.revision == 0
                    || patch.revision > MAX_SAFE_INTEGER_ID
                    || patch.width == 0
                    || patch.height == 0
                    || usize::from(patch.width).checked_mul(usize::from(patch.height))
                        != Some(count)
                    || usize::from(patch.pixel_x) + usize::from(patch.width) > TILE_PIXELS
                    || usize::from(patch.pixel_z) + usize::from(patch.height) > TILE_PIXELS
                    || patch.total_chunks != total_chunks
                    || patch.completed_chunks > patch.total_chunks
                {
                    return Err(CoreError::InvalidChunk);
                }
                descriptor[0] = 1;
                descriptor[4] = patch.revision as u32;
                descriptor[5] = (patch.revision >> 32) as u32;
                descriptor[6] = u32::from(patch.pixel_x);
                descriptor[7] = u32::from(patch.pixel_z);
                descriptor[8] = u32::from(patch.width);
                descriptor[9] = u32::from(patch.height);
                descriptor[10] = patch.completed_chunks;
                descriptor[11] = patch.total_chunks;
                append_pixels(
                    &mut descriptor,
                    &mut colors,
                    &mut heights,
                    &mut statuses,
                    &patch.colors,
                    &patch.heights,
                    &patch.statuses,
                )?;
            }
            MapResultPayload::Ready(tile) => {
                let count = validate_pixels(&tile.colors, &tile.heights, &tile.statuses, false)?;
                let total_chunks = tile_chunk_count(key.blocks_per_pixel())?;
                let reported = tile
                    .successful_chunks
                    .checked_add(tile.missing_chunks)
                    .and_then(|value| value.checked_add(tile.unsupported_chunks))
                    .and_then(|value| value.checked_add(tile.corrupt_chunks))
                    .ok_or(CoreError::ResourceLimit)?;
                let expected_origin = tile.request.bounds()?;
                if tile.request.tile_x != key.tile_x()
                    || tile.request.tile_z != key.tile_z()
                    || tile.request.blocks_per_pixel != key.blocks_per_pixel()
                    || (tile.origin_block_x, tile.origin_block_z)
                        != (expected_origin.0, expected_origin.1)
                    || count != TILE_PIXELS * TILE_PIXELS
                    || reported != total_chunks
                {
                    return Err(CoreError::InvalidChunk);
                }
                validate_ready_status_counts(tile, key.blocks_per_pixel())?;
                descriptor[0] = 2;
                descriptor[8] = TILE_PIXELS as u32;
                descriptor[9] = TILE_PIXELS as u32;
                descriptor[10] = total_chunks;
                descriptor[11] = total_chunks;
                descriptor[12] = tile.successful_chunks;
                descriptor[13] = tile.missing_chunks;
                descriptor[14] = tile.unsupported_chunks;
                descriptor[15] = tile.corrupt_chunks;
                append_pixels(
                    &mut descriptor,
                    &mut colors,
                    &mut heights,
                    &mut statuses,
                    &tile.colors,
                    &tile.heights,
                    &tile.statuses,
                )?;
                terminal_item_count += 1;
            }
            MapResultPayload::Failed(failure) => {
                descriptor[0] = 3;
                descriptor[16] =
                    u32::try_from(colors.len()).map_err(|_| CoreError::ResourceLimit)?;
                descriptor[18] = match failure {
                    MapFailureKind::ReadFailed => 1,
                    MapFailureKind::WorkerPanicked => 2,
                    MapFailureKind::EncodeFailed => 3,
                };
                terminal_item_count += 1;
            }
        }
        descriptors.extend(descriptor);
    }

    let metadata = format!(
        r#"{{"sessionId":{session_id},"viewportTaskId":{viewport_task_id},"batchSequence":{batch_sequence},"itemCount":{},"terminalItemCount":{terminal_item_count},"hasMore":{has_more},"state":"{}"}}"#,
        items.len(),
        state.as_str(),
    );
    let encoded = encode(
        4,
        vec![
            Section {
                id: 20,
                scalar: 4,
                count: u32::try_from(descriptors.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: u32_bytes(descriptors.into_iter())?,
            },
            Section {
                id: 21,
                scalar: 4,
                count: u32::try_from(colors.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: u32_bytes(colors.into_iter())?,
            },
            Section {
                id: 22,
                scalar: 5,
                count: u32::try_from(heights.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: i32_bytes(heights.into_iter())?,
            },
            Section {
                id: 23,
                scalar: 1,
                count: u32::try_from(statuses.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: statuses,
            },
        ],
        &metadata,
    )?;
    if encoded.len() > MAP_BATCH_MAX_BYTES {
        return Err(CoreError::ResourceLimit);
    }
    Ok(encoded)
}

fn append_pixels(
    descriptor: &mut [u32; DESCRIPTOR_WORDS],
    all_colors: &mut Vec<u32>,
    all_heights: &mut Vec<i32>,
    all_statuses: &mut Vec<u8>,
    colors: &[u32],
    heights: &[i32],
    statuses: &[u8],
) -> Result<(), CoreError> {
    descriptor[16] = u32::try_from(all_colors.len()).map_err(|_| CoreError::ResourceLimit)?;
    descriptor[17] = u32::try_from(colors.len()).map_err(|_| CoreError::ResourceLimit)?;
    all_colors.extend_from_slice(colors);
    all_heights.extend_from_slice(heights);
    all_statuses.extend_from_slice(statuses);
    Ok(())
}

fn validate_pixels(
    colors: &[u32],
    heights: &[i32],
    statuses: &[u8],
    allow_unloaded: bool,
) -> Result<usize, CoreError> {
    if colors.len() != heights.len()
        || colors.len() != statuses.len()
        || colors.iter().any(|color| *color > 0x00ff_ffff)
        || statuses
            .iter()
            .any(|status| *status > if allow_unloaded { 4 } else { 3 })
        || statuses
            .iter()
            .zip(heights)
            .any(|(status, height)| *status != 0 && *height != 0)
    {
        return Err(CoreError::InvalidChunk);
    }
    Ok(colors.len())
}

fn tile_chunk_count(blocks_per_pixel: u8) -> Result<u32, CoreError> {
    let chunks_per_side = (TILE_PIXELS as u32)
        .checked_mul(u32::from(blocks_per_pixel))
        .ok_or(CoreError::ResourceLimit)?
        / 16;
    chunks_per_side
        .checked_mul(chunks_per_side)
        .ok_or(CoreError::ResourceLimit)
}

fn validate_ready_status_counts(
    tile: &mcwe_core::map::MapTile,
    blocks_per_pixel: u8,
) -> Result<(), CoreError> {
    let mut pixels = [0_u32; 4];
    for status in &tile.statuses {
        pixels[usize::from(*status)] += 1;
    }
    let pixels_per_chunk = u32::from(16 / blocks_per_pixel).pow(2);
    let expected = [
        tile.successful_chunks,
        tile.missing_chunks,
        tile.unsupported_chunks,
        tile.corrupt_chunks,
    ];
    if pixels
        .iter()
        .zip(expected)
        .any(|(actual, chunks)| *actual != chunks * pixels_per_chunk)
    {
        return Err(CoreError::InvalidChunk);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tasks::{MapJobKey, MapResultIdentity};
    use mcwe_core::map::{MapTile, MapTilePatch, MapTileRequest};

    fn partial(revision: u64) -> MapResultItem {
        let key = MapJobKey::new(7, -1, 2, 1).unwrap();
        MapResultItem {
            identity: MapResultIdentity::new(7, 9, key).unwrap(),
            payload: MapResultPayload::Partial(MapTilePatch {
                request: MapTileRequest {
                    tile_x: -1,
                    tile_z: 2,
                    blocks_per_pixel: 1,
                },
                revision,
                pixel_x: 8,
                pixel_z: 16,
                width: 2,
                height: 1,
                colors: vec![0x0012_3456, 0],
                heights: vec![64, 0],
                statuses: vec![0, 4],
                completed_chunks: 1,
                total_chunks: 256,
            }),
        }
    }

    fn failed() -> MapResultItem {
        let key = MapJobKey::new(7, 1, 2, 1).unwrap();
        MapResultItem {
            identity: MapResultIdentity::new(7, 9, key).unwrap(),
            payload: MapResultPayload::Failed(MapFailureKind::ReadFailed),
        }
    }

    fn ready() -> MapResultItem {
        let key = MapJobKey::new(7, 0, 2, 1).unwrap();
        MapResultItem {
            identity: MapResultIdentity::new(7, 9, key).unwrap(),
            payload: MapResultPayload::Ready(std::sync::Arc::new(MapTile {
                request: MapTileRequest {
                    tile_x: 0,
                    tile_z: 2,
                    blocks_per_pixel: 1,
                },
                origin_block_x: 0,
                origin_block_z: 512,
                colors: vec![0; TILE_PIXELS * TILE_PIXELS],
                heights: vec![0; TILE_PIXELS * TILE_PIXELS],
                statuses: vec![1; TILE_PIXELS * TILE_PIXELS],
                successful_chunks: 0,
                missing_chunks: 256,
                unsupported_chunks: 0,
                corrupt_chunks: 0,
            })),
        }
    }

    #[test]
    fn encodes_mixed_batch_with_fixed_descriptors() {
        let bytes = encode_map_batch_v1(
            7,
            9,
            1,
            &[partial((1_u64 << 32) + 3), ready(), failed()],
            false,
            MapBatchState::Items,
        )
        .unwrap();
        assert_eq!(u16::from_le_bytes(bytes[6..8].try_into().unwrap()), 4);
        assert!(bytes.len() <= MAP_BATCH_MAX_BYTES);
        let descriptor_offset = u32::from_le_bytes(bytes[36..40].try_into().unwrap()) as usize;
        let word = |index: usize| {
            u32::from_le_bytes(
                bytes[descriptor_offset + index * 4..descriptor_offset + index * 4 + 4]
                    .try_into()
                    .unwrap(),
            )
        };
        assert_eq!(word(0), 1);
        assert_eq!(word(1) as i32, -1);
        assert_eq!((word(4), word(5)), (3, 1));
        assert_eq!(word(DESCRIPTOR_WORDS), 2);
        assert_eq!(word(DESCRIPTOR_WORDS * 2), 3);
        assert_eq!(word(DESCRIPTOR_WORDS * 2 + 18), 1);
    }

    #[test]
    fn map_batch_v1_golden_hash_is_stable() {
        let bytes = encode_map_batch_v1(
            7,
            9,
            1,
            &[partial((1_u64 << 32) + 3), failed()],
            false,
            MapBatchState::Items,
        )
        .unwrap();
        let hash = bytes.iter().fold(0xcbf2_9ce4_8422_2325_u64, |hash, byte| {
            (hash ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3)
        });
        assert_eq!(hash, 15_154_743_704_573_164_615);
    }

    #[test]
    fn rejects_identity_revision_and_state_mismatches() {
        assert!(encode_map_batch_v1(8, 9, 1, &[partial(1)], false, MapBatchState::Items,).is_err());
        assert!(encode_map_batch_v1(7, 9, 1, &[partial(0)], false, MapBatchState::Items,).is_err());
        assert!(encode_map_batch_v1(7, 9, 1, &[], false, MapBatchState::Items).is_err());
        assert!(
            encode_map_batch_v1(7, 9, 1, &[partial(1)], false, MapBatchState::Timeout,).is_err()
        );
    }
}
