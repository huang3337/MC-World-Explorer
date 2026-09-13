use super::header::{encode, i32_bytes, u32_bytes, Section};
use mcwe_core::{
    area::{ChunkLoadStatus, SurfaceArea},
    limits::{PREVIEW_SIDE, SURFACE_CELLS},
    CoreError,
};

pub fn encode_surface_v1(
    area: &SurfaceArea,
    session_id: u64,
    task_id: u64,
    center: (i32, i32, i32),
    center_source: &str,
    world_spawn: Option<(i32, i32, i32)>,
) -> Result<Vec<u8>, CoreError> {
    let side = if area.cells.len() == SURFACE_CELLS {
        144
    } else {
        PREVIEW_SIDE
    };
    if area.cells.len() != side * side || area.statuses.len() != side * side {
        return Err(CoreError::InvalidChunk);
    }
    let legacy_origin_x = area
        .center
        .x
        .checked_sub(4)
        .and_then(|value| value.checked_mul(16))
        .ok_or(CoreError::ResourceLimit)?;
    let legacy_origin_z = area
        .center
        .z
        .checked_sub(4)
        .and_then(|value| value.checked_mul(16))
        .ok_or(CoreError::ResourceLimit)?;
    let (expected_origin_x, expected_origin_z) = if side == PREVIEW_SIDE {
        let b = mcwe_core::area::PreviewBounds::new(center.0, center.2)?;
        (b.min_x, b.min_z)
    } else {
        (legacy_origin_x, legacy_origin_z)
    };
    if area.origin_block_x != expected_origin_x
        || area.origin_block_z != expected_origin_z
        || center.0.div_euclid(16) != area.center.x
        || center.2.div_euclid(16) != area.center.z
        || !matches!(
            center_source,
            "playerRespawn" | "worldSpawn" | "originFallback"
        )
        || (center_source == "worldSpawn" && world_spawn != Some(center))
        || (center_source == "originFallback" && center != (0, 0, 0))
        || area.cells.iter().zip(&area.statuses).any(|(cell, status)| {
            cell.color > 0x00ff_ffff || (*status != ChunkLoadStatus::Success && cell.height != 0)
        })
    {
        return Err(CoreError::InvalidChunk);
    }
    let status_counts = chunk_status_counts(area, side)?;
    if status_counts[4] != 0
        || status_counts[0] != area.successful_chunks
        || status_counts[2].checked_add(status_counts[3]) != Some(area.failed_chunks)
        || area
            .successful_chunks
            .checked_add(status_counts[1])
            .and_then(|value| value.checked_add(area.failed_chunks))
            != Some(status_counts.iter().sum())
    {
        return Err(CoreError::InvalidChunk);
    }
    let warnings = surface_warnings(&status_counts);
    let world_spawn = world_spawn.map_or_else(
        || String::from("null"),
        |(x, y, z)| format!(r#"{{"x":{x},"y":{y},"z":{z}}}"#),
    );
    let metadata = format!(
        r#"{{"sessionId":{session_id},"taskId":{task_id},"originBlockX":{},"originBlockZ":{},"centerChunkX":{},"centerChunkZ":{},"centerX":{},"centerY":{},"centerZ":{},"centerSource":"{center_source}","worldSpawn":{world_spawn},"successfulChunks":{},"failedChunks":{},"warnings":{warnings}}}"#,
        area.origin_block_x,
        area.origin_block_z,
        area.center.x,
        area.center.z,
        center.0,
        center.1,
        center.2,
        area.successful_chunks,
        area.failed_chunks
    );
    encode(
        1,
        vec![
            Section {
                id: 1,
                scalar: 4,
                count: (side * side) as u32,
                bytes: u32_bytes(area.cells.iter().map(|c| c.color))?,
            },
            Section {
                id: 2,
                scalar: 5,
                count: (side * side) as u32,
                bytes: i32_bytes(area.cells.iter().map(|c| c.height))?,
            },
            Section {
                id: 3,
                scalar: 1,
                count: (side * side) as u32,
                bytes: area.statuses.iter().map(|s| *s as u8).collect(),
            },
        ],
        &metadata,
    )
}

fn chunk_status_counts(area: &SurfaceArea, side: usize) -> Result<[u16; 5], CoreError> {
    let mut counts = [0_u16; 5];
    let ox = i64::from(area.origin_block_x);
    let oz = i64::from(area.origin_block_z);
    for cz in oz.div_euclid(16)..=(oz + side as i64 - 1).div_euclid(16) {
        for cx in ox.div_euclid(16)..=(ox + side as i64 - 1).div_euclid(16) {
            let x0 = (cx * 16 - ox).max(0) as usize;
            let z0 = (cz * 16 - oz).max(0) as usize;
            let x1 = (cx * 16 + 16 - ox).min(side as i64) as usize;
            let z1 = (cz * 16 + 16 - oz).min(side as i64) as usize;
            let first = area.statuses[z0 * side + x0];
            for z in z0..z1 {
                if area.statuses[z * side + x0..z * side + x1]
                    .iter()
                    .any(|s| *s != first)
                {
                    return Err(CoreError::InvalidChunk);
                }
            }
            counts[first as usize] += 1;
        }
    }
    Ok(counts)
}

fn surface_warnings(counts: &[u16; 5]) -> String {
    let labels = ["success", "missing", "unsupported", "corrupt", "cancelled"];
    let mut warnings = String::from("[");
    let mut first = true;
    for status in 1..labels.len() {
        if counts[status] == 0 {
            continue;
        }
        if !first {
            warnings.push(',');
        }
        first = false;
        warnings.push_str(&format!(
            r#"{{"kind":"chunkStatus","status":"{}","chunks":{}}}"#,
            labels[status], counts[status]
        ));
    }
    warnings.push(']');
    warnings
}

#[cfg(test)]
mod tests {
    use super::*;
    use mcwe_core::{anvil::ChunkCoordinate, area::ChunkLoadStatus, surface::SurfaceCell};

    fn area(status: ChunkLoadStatus) -> SurfaceArea {
        SurfaceArea {
            origin_block_x: -64,
            origin_block_z: -64,
            center: ChunkCoordinate { x: 0, z: 0 },
            cells: vec![SurfaceCell::default(); SURFACE_CELLS],
            statuses: vec![status; SURFACE_CELLS],
            successful_chunks: u16::from(status == ChunkLoadStatus::Success) * 81,
            failed_chunks: u16::from(matches!(
                status,
                ChunkLoadStatus::Unsupported | ChunkLoadStatus::Corrupt
            )) * 81,
        }
    }

    #[test]
    fn emits_aggregated_status_warning() {
        let encoded = encode_surface_v1(
            &area(ChunkLoadStatus::Missing),
            7,
            9,
            (1, 2, 3),
            "worldSpawn",
            Some((1, 2, 3)),
        )
        .unwrap();
        let metadata_offset = u32::from_le_bytes(encoded[16..20].try_into().unwrap()) as usize;
        let metadata = std::str::from_utf8(&encoded[metadata_offset..]).unwrap();
        assert!(metadata.contains(r#""status":"missing","chunks":81"#));
    }

    #[test]
    fn rejects_inconsistent_chunk_summary() {
        let mut value = area(ChunkLoadStatus::Success);
        value.successful_chunks = 80;
        assert!(matches!(
            encode_surface_v1(&value, 1, 2, (0, 0, 0), "originFallback", None),
            Err(CoreError::InvalidChunk)
        ));
    }

    #[test]
    fn rejects_inconsistent_coordinates_colors_and_failed_heights() {
        let mut value = area(ChunkLoadStatus::Success);
        value.origin_block_x += 16;
        assert!(encode_surface_v1(&value, 1, 2, (0, 0, 0), "originFallback", None).is_err());

        let mut value = area(ChunkLoadStatus::Success);
        value.cells[0].color = 0xff00_0000;
        assert!(encode_surface_v1(&value, 1, 2, (0, 0, 0), "originFallback", None).is_err());

        let mut value = area(ChunkLoadStatus::Missing);
        value.cells[0].height = 1;
        assert!(encode_surface_v1(&value, 1, 2, (0, 0, 0), "originFallback", None).is_err());

        assert!(encode_surface_v1(
            &area(ChunkLoadStatus::Success),
            1,
            2,
            (16, 0, 0),
            "worldSpawn",
            Some((16, 0, 0)),
        )
        .is_err());

        assert!(encode_surface_v1(
            &area(ChunkLoadStatus::Success),
            1,
            2,
            (0, 0, 0),
            "worldSpawn",
            None,
        )
        .is_err());
    }
}
