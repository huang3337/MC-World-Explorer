#![cfg(windows)]

use fastnbt::Value;
use mcwe_core::{
    anvil::ChunkCoordinate,
    area::{load_surface_area, ChunkLoadStatus},
    cancel::{Cancellation, NeverCancel},
    chunk::decode_chunk,
    surface::{block_color, sample_chunk},
    world::{
        MapLoadAnchor, MapLoadAnchorSource, PreviewCenter, PreviewCenterSource, WorldInfo,
        WorldPosition,
    },
    CoreError, WorldSource,
};
use std::{
    collections::HashMap,
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

const SECTOR: usize = 4096;

struct Fixture {
    root: PathBuf,
    region_file: Option<PathBuf>,
}

impl Fixture {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/mcwe-core-tests");
        fs::create_dir_all(&base).unwrap();
        let root = base.canonicalize().unwrap().join(format!(
            "surface-slice-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        Self {
            root,
            region_file: None,
        }
    }

    fn source(&self) -> WorldSource {
        WorldSource::new(&self.root).unwrap()
    }

    fn write_region(&mut self, entries: &[(usize, u8, Vec<u8>)]) {
        fs::create_dir(self.root.join("region")).unwrap();
        let mut bytes = vec![0_u8; (2 + entries.len()) * SECTOR];
        for (index, (slot, compression, payload)) in entries.iter().enumerate() {
            let sector = 2 + index;
            let location = (sector as u32).to_be_bytes();
            let header = slot * 4;
            bytes[header..header + 3].copy_from_slice(&location[1..]);
            bytes[header + 3] = 1;
            let start = sector * SECTOR;
            let length = u32::try_from(payload.len() + 1).unwrap();
            bytes[start..start + 4].copy_from_slice(&length.to_be_bytes());
            bytes[start + 4] = *compression;
            bytes[start + 5..start + 5 + payload.len()].copy_from_slice(payload);
        }
        let path = self.root.join("region/r.0.0.mca");
        fs::write(&path, bytes).unwrap();
        self.region_file = Some(path);
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        if let Some(path) = &self.region_file {
            let _ = fs::remove_file(path);
            let _ = fs::remove_dir(self.root.join("region"));
        }
        let _ = fs::remove_dir(&self.root);
    }
}

fn palette_entry(name: &str) -> Value {
    Value::Compound(HashMap::from([(
        "Name".to_owned(),
        Value::String(name.to_owned()),
    )]))
}

fn section(y: i8, palette: Vec<Value>, data: Option<Vec<i64>>) -> Value {
    let mut states = HashMap::from([("palette".to_owned(), Value::List(palette))]);
    if let Some(data) = data {
        states.insert(
            "data".to_owned(),
            Value::LongArray(fastnbt::LongArray::new(data)),
        );
    }
    Value::Compound(HashMap::from([
        ("Y".to_owned(), Value::Byte(y)),
        ("block_states".to_owned(), Value::Compound(states)),
    ]))
}

fn chunk_bytes(x: i32, z: i32, sections: Vec<Value>) -> Vec<u8> {
    fastnbt::to_bytes(&HashMap::from([
        ("xPos".to_owned(), Value::Int(x)),
        ("zPos".to_owned(), Value::Int(z)),
        ("sections".to_owned(), Value::List(sections)),
    ]))
    .unwrap()
}

fn world(spawn_x: i32, spawn_z: i32) -> WorldInfo {
    let position = WorldPosition {
        x: spawn_x,
        y: 0,
        z: spawn_z,
    };
    WorldInfo {
        data_version: None,
        name: String::new(),
        world_spawn: Some(position.clone()),
        player_respawn: None,
        player_exit: None,
        preview_center: PreviewCenter {
            position: position.clone(),
            source: PreviewCenterSource::WorldSpawn,
        },
        map_load_anchor: MapLoadAnchor {
            position,
            source: MapLoadAnchorSource::WorldSpawn,
        },
    }
}

#[test]
fn extreme_negative_spawn_is_a_controlled_failure() {
    let fixture = Fixture::new();
    let source = fixture.source();
    assert!(matches!(
        load_surface_area(&source, &world(i32::MIN, 0), &NeverCancel),
        Err(CoreError::ResourceLimit)
    ));
}

#[test]
fn empty_negative_spawn_area_has_fixed_dimensions_and_floor_origin() {
    let fixture = Fixture::new();
    let area = load_surface_area(&fixture.source(), &world(-1, -1), &NeverCancel).unwrap();
    assert_eq!(area.center, ChunkCoordinate { x: -1, z: -1 });
    assert_eq!((area.origin_block_x, area.origin_block_z), (-513, -513));
    assert_eq!(area.cells.len(), 1024 * 1024);
    assert_eq!(area.statuses.len(), 1024 * 1024);
    assert_eq!((area.successful_chunks, area.failed_chunks), (0, 0));
    assert!(area
        .statuses
        .iter()
        .all(|status| *status == ChunkLoadStatus::Missing));
}

#[test]
fn samples_the_highest_non_air_across_section_gaps() {
    let mut upper_data = vec![0_i64; 256];
    upper_data[0] = 1;
    let bytes = chunk_bytes(
        0,
        0,
        vec![
            section(-1, vec![palette_entry("minecraft:dirt")], None),
            section(
                1,
                vec![
                    palette_entry("minecraft:cave_air"),
                    palette_entry("minecraft:stone"),
                ],
                Some(upper_data),
            ),
        ],
    );
    let chunk = decode_chunk(&bytes, ChunkCoordinate { x: 0, z: 0 }, &NeverCancel).unwrap();
    let surface = sample_chunk(&chunk, &NeverCancel).unwrap();
    assert_eq!(surface.len(), 256);
    assert_eq!(surface[0].height, 16);
    assert_eq!(surface[0].color, block_color("minecraft:stone"));
    assert_eq!(surface[1].height, -1);
    assert_eq!(surface[1].color, block_color("minecraft:dirt"));
}

#[test]
fn representative_colors_match_the_java_palette_baseline() {
    for (name, expected) in [
        ("minecraft:water", 0x3F76E4),
        ("minecraft:lava", 0xF36B21),
        ("minecraft:snow_block", 0xF3F6F7),
        ("minecraft:packed_ice", 0x9DC3E6),
        ("minecraft:sand", 0xE5D18D),
        ("minecraft:grass_block", 0x6F9E43),
        ("minecraft:oak_leaves", 0x4E7F36),
        ("minecraft:dirt", 0x836044),
        ("minecraft:oak_log", 0x8A6A42),
        ("minecraft:stone", 0x777A7C),
        ("minecraft:clay", 0x9BA6B2),
        ("minecraft:terracotta", 0xA45C44),
        ("minecraft:netherrack", 0x772C2C),
        ("minecraft:obsidian", 0x35383D),
        ("minecraft:quartz_block", 0xDDDAD2),
        ("minecraft:red_wool", 0xB84A3E),
        ("minecraft:copper_block", 0xC9783D),
        ("minecraft:gold_block", 0xD7B94A),
        ("minecraft:green_wool", 0x609D4B),
        ("minecraft:cyan_wool", 0x3E8D91),
        ("minecraft:blue_wool", 0x496EA8),
        ("minecraft:purple_wool", 0x8C5A9E),
        ("minecraft:pink_wool", 0xC7788D),
        ("minecraft:brown_wool", 0x76543A),
        ("minecraft:iron_block", 0x858A8C),
    ] {
        assert_eq!(block_color(name), expected, "{name}");
    }
    assert_eq!(
        block_color("example:moon_dust"),
        block_color("example:moon_dust")
    );
}

#[test]
fn preserves_success_while_isolating_corrupt_unsupported_and_missing_chunks() {
    let valid = chunk_bytes(
        0,
        0,
        vec![section(
            0,
            vec![palette_entry("minecraft:grass_block")],
            None,
        )],
    );
    let mut fixture = Fixture::new();
    fixture.write_region(&[
        (0, 3, valid),
        (1, 3, vec![1, 2, 3]),
        (2, 4, vec![10, 0, 0, 0]),
    ]);
    let area = load_surface_area(&fixture.source(), &world(0, 0), &NeverCancel).unwrap();
    let status = |chunk_dx: usize| area.statuses[512 * 1024 + (32 + chunk_dx) * 16];
    assert_eq!(status(0), ChunkLoadStatus::Success);
    assert_eq!(status(1), ChunkLoadStatus::Corrupt);
    assert_eq!(status(2), ChunkLoadStatus::Unsupported);
    assert_eq!(area.statuses[0], ChunkLoadStatus::Missing);
    assert_eq!((area.successful_chunks, area.failed_chunks), (1, 2));
    assert_eq!(area.cells[512 * 1024 + 512].height, 15);
    assert_eq!(
        area.cells[512 * 1024 + 512].color,
        block_color("minecraft:grass_block")
    );
}

struct AlwaysCancelled;

impl Cancellation for AlwaysCancelled {
    fn is_cancelled(&self) -> bool {
        true
    }
}

#[test]
fn cancellation_aborts_the_area_instead_of_becoming_a_local_failure() {
    let fixture = Fixture::new();
    assert!(matches!(
        load_surface_area(&fixture.source(), &world(0, 0), &AlwaysCancelled),
        Err(CoreError::Cancelled)
    ));
}
