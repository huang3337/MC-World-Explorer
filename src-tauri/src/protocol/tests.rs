use super::{encode_mesh_v1, encode_surface_v1};
use mcwe_core::{
    anvil::ChunkCoordinate,
    area::{ChunkLoadStatus, SurfaceArea},
    limits::{MAX_RESPONSE_BYTES, SURFACE_CELLS},
    mesh::{
        ChunkFailure, ChunkRect, MeshBatch, MeshBounds, MeshData, MeshWarning, MeshWarningKind,
        RenderLayer,
    },
    surface::SurfaceCell,
};
use std::collections::BTreeSet;

#[derive(Debug)]
struct Parsed<'a> {
    kind: u16,
    sections: Vec<ParsedSection>,
    metadata: &'a str,
}

#[derive(Debug)]
struct ParsedSection {
    id: u16,
    scalar: u16,
    offset: usize,
    byte_len: usize,
    count: usize,
}

fn parse(bytes: &[u8]) -> Result<Parsed<'_>, &'static str> {
    if !(32..=MAX_RESPONSE_BYTES).contains(&bytes.len()) || bytes.get(0..4) != Some(b"MCWE") {
        return Err("header");
    }
    let u16_at = |at| {
        bytes
            .get(at..at + 2)
            .and_then(|value| value.try_into().ok())
            .map(u16::from_le_bytes)
            .ok_or("truncated")
    };
    let u32_at = |at| {
        bytes
            .get(at..at + 4)
            .and_then(|value| value.try_into().ok())
            .map(u32::from_le_bytes)
            .ok_or("truncated")
    };
    if u16_at(4)? != 1 || u32_at(8)? != 32 || u32_at(12)? as usize != bytes.len() {
        return Err("header");
    }
    let kind = u16_at(6)?;
    let expected: &[(u16, u16)] = match kind {
        1 => &[(1, 4), (2, 5), (3, 1)],
        2 => &[(10, 6), (11, 6), (12, 4), (13, 4)],
        _ => return Err("kind"),
    };
    if u32_at(24)? != 32 || u32_at(28)? as usize != expected.len() {
        return Err("table");
    }
    let table_end = 32 + expected.len() * 16;
    let metadata_offset = u32_at(16)? as usize;
    let metadata_len = u32_at(20)? as usize;
    if !metadata_offset.is_multiple_of(4)
        || metadata_offset.checked_add(metadata_len) != Some(bytes.len())
        || metadata_offset < table_end
    {
        return Err("metadata");
    }
    let mut ids = BTreeSet::new();
    let mut ranges = vec![(0, table_end), (metadata_offset, bytes.len())];
    let mut sections = Vec::with_capacity(expected.len());
    let mut expected_offset = (table_end + 3) & !3;
    for (index, &(expected_id, expected_scalar)) in expected.iter().enumerate() {
        let at = 32 + index * 16;
        let id = u16_at(at)?;
        let scalar = u16_at(at + 2)?;
        let offset = u32_at(at + 4)? as usize;
        let byte_len = u32_at(at + 8)? as usize;
        let count = u32_at(at + 12)? as usize;
        let width = match scalar {
            1 | 2 => 1,
            3 => 2,
            4..=6 => 4,
            _ => return Err("scalar"),
        };
        if id != expected_id
            || scalar != expected_scalar
            || !ids.insert(id)
            || !offset.is_multiple_of(4)
            || offset != expected_offset
            || count.checked_mul(width) != Some(byte_len)
            || offset.checked_add(byte_len).is_none()
            || offset + byte_len > metadata_offset
            || ranges
                .iter()
                .any(|(start, end)| offset < *end && offset + byte_len > *start)
        {
            return Err("section");
        }
        ranges.push((offset, offset + byte_len));
        expected_offset = (offset + byte_len + 3) & !3;
        sections.push(ParsedSection {
            id,
            scalar,
            offset,
            byte_len,
            count,
        });
    }
    if expected_offset != metadata_offset {
        return Err("gap");
    }
    let metadata = std::str::from_utf8(&bytes[metadata_offset..]).map_err(|_| "utf8")?;
    let value: serde_json::Value = serde_json::from_str(metadata).map_err(|_| "json")?;
    if !value.is_object() {
        return Err("json");
    }
    Ok(Parsed {
        kind,
        sections,
        metadata,
    })
}

fn surface_fixture() -> Vec<u8> {
    let mut cells = vec![SurfaceCell::default(); SURFACE_CELLS];
    cells[0] = SurfaceCell {
        color: 0x0012_3456,
        height: -64,
    };
    let area = SurfaceArea {
        origin_block_x: -48,
        origin_block_z: 48,
        center: ChunkCoordinate { x: 1, z: 7 },
        cells,
        statuses: vec![ChunkLoadStatus::Success; SURFACE_CELLS],
        successful_chunks: 81,
        failed_chunks: 0,
    };
    encode_surface_v1(
        &area,
        11,
        12,
        (24, 70, 120),
        "playerRespawn",
        Some((32, 72, 128)),
    )
    .unwrap()
}

fn mesh_fixture() -> MeshData {
    MeshData {
        origin_world_x: -16,
        origin_world_z: 32,
        bounds: Some(MeshBounds {
            min_x: 0.0,
            min_y: 64.0,
            min_z: 0.0,
            max_x: 1.0,
            max_y: 65.0,
            max_z: 0.0,
        }),
        positions: vec![
            0.0, 64.0, 0.0, 1.0, 64.0, 0.0, 1.0, 65.0, 0.0, 0.0, 65.0, 0.0,
        ],
        normals: vec![
            0.0, 0.0, -1.0, 0.0, 0.0, -1.0, 0.0, 0.0, -1.0, 0.0, 0.0, -1.0,
        ],
        indices: vec![0, 1, 2, 0, 2, 3],
        batches: vec![MeshBatch {
            layer: RenderLayer::Fallback,
            rgb: 0x00ab_cdef,
            alpha: 1.0,
            first_index: 0,
            index_count: 6,
            block_count: 1,
            face_count: 1,
        }],
        block_count: 1,
        face_count: 1,
        warnings: vec![
            MeshWarning {
                kind: MeshWarningKind::FallbackBlock,
                coordinate: ChunkCoordinate { x: -1, z: 2 },
                failure: None,
                block_name: Some(String::from("模组:\"方块\\样例")),
                occurrences: 3,
            },
            MeshWarning {
                kind: MeshWarningKind::NeighborUnavailable,
                coordinate: ChunkCoordinate { x: -2, z: 2 },
                failure: Some(ChunkFailure::Missing),
                block_name: None,
                occurrences: 1,
            },
        ],
    }
}

fn fnv1a64(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

#[test]
fn large_surface_java_bounds_golden() {
    let cells = 1024 * 1024;
    let mut area = SurfaceArea {
        origin_block_x: -513,
        origin_block_z: -495,
        center: ChunkCoordinate { x: -1, z: 1 },
        cells: vec![SurfaceCell::default(); cells],
        statuses: vec![ChunkLoadStatus::Missing; cells],
        successful_chunks: 0,
        failed_chunks: 0,
    };
    let bytes =
        encode_surface_v1(&area, 1, 2, (-1, 64, 17), "worldSpawn", Some((-1, 64, 17))).unwrap();
    assert!(parse(&bytes).is_ok());
    assert_eq!(fnv1a64(&bytes), 252_811_299_370_147_227);
    area.statuses[cells - 1] = ChunkLoadStatus::Success;
    assert!(
        encode_surface_v1(&area, 1, 2, (-1, 64, 17), "worldSpawn", Some((-1, 64, 17))).is_err()
    );
}

#[test]
fn surface_v1_golden_layout_is_stable() {
    let bytes = surface_fixture();
    let parsed = parse(&bytes).unwrap();
    assert_eq!(parsed.kind, 1);
    assert_eq!(
        parsed.sections.iter().map(|s| s.id).collect::<Vec<_>>(),
        [1, 2, 3]
    );
    assert_eq!(
        parsed.sections.iter().map(|s| s.scalar).collect::<Vec<_>>(),
        [4, 5, 1]
    );
    assert!(parsed
        .sections
        .iter()
        .all(|section| section.count == SURFACE_CELLS));
    assert_eq!(
        &bytes[parsed.sections[0].offset..parsed.sections[0].offset + 4],
        &[0x56, 0x34, 0x12, 0]
    );
    assert_eq!(fnv1a64(&bytes), 13_390_334_150_084_859_276);
}

#[test]
fn mesh_v1_golden_layout_preserves_bounds_and_utf8_warnings() {
    let bytes = encode_mesh_v1(
        &mesh_fixture(),
        ChunkRect {
            min_x: -1,
            min_z: 2,
            width: 1,
            depth: 1,
        },
        21,
        22,
    )
    .unwrap();
    let parsed = parse(&bytes).unwrap();
    assert_eq!(parsed.kind, 2);
    assert_eq!(
        parsed
            .sections
            .iter()
            .map(|s| s.byte_len)
            .collect::<Vec<_>>(),
        [48, 48, 24, 32]
    );
    let metadata: serde_json::Value = serde_json::from_str(parsed.metadata).unwrap();
    assert_eq!(metadata["originWorldX"], -16);
    assert_eq!(metadata["bounds"]["maxY"], 65.0);
    assert_eq!(metadata["warnings"][0]["blockName"], "模组:\"方块\\样例");
    assert_eq!(fnv1a64(&bytes), 9_468_971_920_670_735_303);
}

#[test]
fn self_parser_rejects_single_field_corruptions() {
    let golden = surface_fixture();
    for mutate in [
        |bytes: &mut Vec<u8>| bytes[4] = 2,
        |bytes: &mut Vec<u8>| bytes[12] = bytes[12].wrapping_add(1),
        |bytes: &mut Vec<u8>| bytes[36] = bytes[36].wrapping_add(1),
        |bytes: &mut Vec<u8>| bytes[40] = bytes[40].wrapping_add(1),
    ] {
        let mut malformed = golden.clone();
        mutate(&mut malformed);
        assert!(parse(&malformed).is_err());
    }
    let mut truncated = golden.clone();
    truncated.pop();
    assert!(parse(&truncated).is_err());
    let mut tailed = golden;
    tailed.push(0);
    assert!(parse(&tailed).is_err());
    let mut overlap = surface_fixture();
    let first_offset = overlap[36..40].to_vec();
    overlap[52..56].copy_from_slice(&first_offset);
    assert!(parse(&overlap).is_err());
}

#[test]
fn mesh_encoder_rejects_nonfinite_non_axis_and_bad_indices() {
    let rect = ChunkRect {
        min_x: -1,
        min_z: 2,
        width: 1,
        depth: 1,
    };
    let mut mesh = mesh_fixture();
    mesh.positions[0] = f32::NAN;
    assert!(encode_mesh_v1(&mesh, rect, 1, 2).is_err());
    let mut mesh = mesh_fixture();
    mesh.normals[0] = 0.5;
    assert!(encode_mesh_v1(&mesh, rect, 1, 2).is_err());
    let mut mesh = mesh_fixture();
    mesh.indices[5] = 4;
    assert!(encode_mesh_v1(&mesh, rect, 1, 2).is_err());
}

#[test]
fn mesh_encoder_rejects_overflowing_or_inconsistent_batches() {
    let rect = ChunkRect {
        min_x: -1,
        min_z: 2,
        width: 1,
        depth: 1,
    };
    let mut mesh = mesh_fixture();
    mesh.batches[0].first_index = u32::MAX;
    mesh.batches[0].index_count = 6;
    assert!(encode_mesh_v1(&mesh, rect, 1, 2).is_err());
    let mut mesh = mesh_fixture();
    mesh.batches[0].face_count = 2;
    assert!(encode_mesh_v1(&mesh, rect, 1, 2).is_err());
    let mut mesh = mesh_fixture();
    mesh.face_count = 2;
    assert!(encode_mesh_v1(&mesh, rect, 1, 2).is_err());
}
