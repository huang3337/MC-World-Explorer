#![cfg(windows)]

use flate2::{
    write::{GzEncoder, ZlibEncoder},
    Compression,
};
use mcwe_core::{
    cancel::{Cancellation, NeverCancel},
    limits::{LEVEL_INPUT_BYTES, NBT_OUTPUT_BYTES},
    world::read_world_info,
    CoreError, WorldSource,
};
use std::{
    collections::HashMap,
    fs,
    io::Write,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

struct Fixture {
    root: PathBuf,
}

impl Fixture {
    fn new(bytes: &[u8]) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/mcwe-core-tests");
        fs::create_dir_all(&base).unwrap();
        let root = base.canonicalize().unwrap().join(format!(
            "level-dat-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        fs::write(root.join("level.dat"), bytes).unwrap();
        Self { root }
    }

    fn read(&self, cancel: &dyn Cancellation) -> Result<mcwe_core::world::WorldInfo, CoreError> {
        let source = WorldSource::new(&self.root)?;
        read_world_info(&source, cancel)
    }

    fn player(&self, name: &str, bytes: &[u8]) {
        fs::create_dir_all(self.root.join("playerdata")).unwrap();
        fs::write(self.root.join("playerdata").join(name), bytes).unwrap();
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        if let Ok(files) = fs::read_dir(self.root.join("playerdata")) {
            for file in files.flatten() {
                let _ = fs::remove_file(file.path());
            }
        }
        let _ = fs::remove_dir(self.root.join("playerdata"));
        let _ = fs::remove_file(self.root.join("level.dat"));
        let _ = fs::remove_dir(&self.root);
    }
}

fn player_nbt(x: f64, y: f64, z: f64, dimension: &str) -> Vec<u8> {
    use fastnbt::Value;
    fastnbt::to_bytes(&HashMap::from([
        (
            "Pos".to_owned(),
            Value::List(vec![Value::Double(x), Value::Double(y), Value::Double(z)]),
        ),
        ("Dimension".to_owned(), Value::String(dimension.to_owned())),
    ]))
    .unwrap()
}

fn valid_nbt() -> Vec<u8> {
    use fastnbt::Value;
    let data = HashMap::from([
        ("DataVersion".to_owned(), Value::Int(3955)),
        ("LevelName".to_owned(), Value::String("合成世界".to_owned())),
        ("SpawnX".to_owned(), Value::Int(-17)),
        ("SpawnY".to_owned(), Value::Int(64)),
        ("SpawnZ".to_owned(), Value::Int(-33)),
    ]);
    fastnbt::to_bytes(&HashMap::from([("Data".to_owned(), Value::Compound(data))])).unwrap()
}

fn compressed(raw: &[u8], gzip: bool) -> Vec<u8> {
    if gzip {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(raw).unwrap();
        encoder.finish().unwrap()
    } else {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(raw).unwrap();
        encoder.finish().unwrap()
    }
}

#[test]
fn reads_raw_gzip_and_zlib_with_negative_spawn() {
    let raw = valid_nbt();
    for bytes in [raw.clone(), compressed(&raw, true), compressed(&raw, false)] {
        let fixture = Fixture::new(&bytes);
        let info = fixture.read(&NeverCancel).unwrap();
        assert_eq!(
            (
                info.preview_center.position.x,
                info.preview_center.position.y,
                info.preview_center.position.z
            ),
            (-17, 64, -33)
        );
        assert_eq!(info.name, "合成世界");
        assert_eq!(info.data_version, Some(3955));
    }
}

#[test]
fn preview_center_matches_java_respawn_world_spawn_origin_order() {
    use fastnbt::Value;
    let player = Value::Compound(HashMap::from([
        ("SpawnX".to_owned(), Value::Int(-710)),
        ("SpawnY".to_owned(), Value::Int(65)),
        ("SpawnZ".to_owned(), Value::Int(-604)),
        (
            "SpawnDimension".to_owned(),
            Value::String("minecraft:overworld".to_owned()),
        ),
    ]));
    let data = Value::Compound(HashMap::from([
        ("SpawnX".to_owned(), Value::Int(256)),
        ("SpawnY".to_owned(), Value::Int(69)),
        ("SpawnZ".to_owned(), Value::Int(-144)),
        ("Player".to_owned(), player),
    ]));
    let bytes = fastnbt::to_bytes(&HashMap::from([("Data".to_owned(), data)])).unwrap();
    let info = Fixture::new(&bytes).read(&NeverCancel).unwrap();
    assert_eq!(
        (
            info.preview_center.position.x,
            info.preview_center.position.y,
            info.preview_center.position.z
        ),
        (-710, 65, -604)
    );
    assert_eq!(
        info.preview_center.source,
        mcwe_core::world::PreviewCenterSource::PlayerRespawn
    );
    assert_eq!(info.world_spawn.unwrap().x, 256);
}

#[test]
fn one_player_uses_last_exit_but_multiplayer_uses_world_spawn() {
    let fixture = Fixture::new(&valid_nbt());
    fixture.player(
        "00000000-0000-0000-0000-000000000001.dat",
        &compressed(&player_nbt(-12.75, 70.5, 31.9, "minecraft:overworld"), true),
    );
    let info = fixture.read(&NeverCancel).unwrap();
    assert_eq!(
        (
            info.map_load_anchor.position.x,
            info.map_load_anchor.position.z
        ),
        (-13, 31)
    );
    assert_eq!(
        info.map_load_anchor.source,
        mcwe_core::world::MapLoadAnchorSource::PlayerExit
    );

    fixture.player(
        "00000000-0000-0000-0000-000000000002.dat",
        &compressed(&player_nbt(900.0, 80.0, 900.0, "minecraft:overworld"), true),
    );
    let info = fixture.read(&NeverCancel).unwrap();
    assert_eq!(
        (
            info.map_load_anchor.position.x,
            info.map_load_anchor.position.z
        ),
        (-17, -33)
    );
    assert_eq!(
        info.map_load_anchor.source,
        mcwe_core::world::MapLoadAnchorSource::WorldSpawn
    );
}

#[test]
fn unusable_single_player_data_falls_back_to_world_spawn() {
    let fixture = Fixture::new(&valid_nbt());
    fixture.player(
        "00000000-0000-0000-0000-000000000001.dat",
        &compressed(&player_nbt(12.0, 70.0, 31.0, "minecraft:the_nether"), true),
    );
    let info = fixture.read(&NeverCancel).unwrap();
    assert_eq!(
        (
            info.map_load_anchor.position.x,
            info.map_load_anchor.position.z
        ),
        (-17, -33)
    );
    assert_eq!(info.player_exit, None);
}

#[test]
fn unrelated_nbt_text_over_the_ipc_budget_does_not_reject_a_bounded_level_dat() {
    use fastnbt::Value;
    let mut data = HashMap::from([
        ("SpawnX".to_owned(), Value::Int(0)),
        ("SpawnY".to_owned(), Value::Int(64)),
        ("SpawnZ".to_owned(), Value::Int(0)),
    ]);
    for index in 0..8 {
        data.insert(
            format!("IgnoredCustomText{index}"),
            Value::String("x".repeat(40 * 1024)),
        );
    }
    let bytes =
        fastnbt::to_bytes(&HashMap::from([("Data".to_owned(), Value::Compound(data))])).unwrap();
    let info = Fixture::new(&bytes).read(&NeverCancel).unwrap();
    assert_eq!(
        info.preview_center.source,
        mcwe_core::world::PreviewCenterSource::WorldSpawn
    );
}

#[test]
fn missing_or_wrong_typed_spawn_uses_origin_fallback() {
    use fastnbt::Value;
    let missing = HashMap::from([("Data".to_owned(), Value::Compound(HashMap::new()))]);
    let wrong = HashMap::from([(
        "Data".to_owned(),
        Value::Compound(HashMap::from([
            ("SpawnX".to_owned(), Value::String("not-an-int".to_owned())),
            ("SpawnY".to_owned(), Value::Int(64)),
            ("SpawnZ".to_owned(), Value::Int(0)),
        ])),
    )]);
    let info = Fixture::new(&fastnbt::to_bytes(&missing).unwrap())
        .read(&NeverCancel)
        .unwrap();
    assert_eq!(info.preview_center.position.x, 0);
    assert_eq!(
        info.preview_center.source,
        mcwe_core::world::PreviewCenterSource::OriginFallback
    );
    let info = Fixture::new(&fastnbt::to_bytes(&wrong).unwrap())
        .read(&NeverCancel)
        .unwrap();
    assert_eq!(
        info.preview_center.source,
        mcwe_core::world::PreviewCenterSource::OriginFallback
    );
}

#[test]
fn rejects_malformed_root_corrupt_compression_and_trailing_bytes() {
    for bytes in [vec![1, 0, 0, 0], vec![0x1f, 0x8b, 0, 0, 0], {
        let mut bytes = valid_nbt();
        bytes.push(0);
        bytes
    }] {
        assert!(matches!(
            Fixture::new(&bytes).read(&NeverCancel),
            Err(CoreError::InvalidNbt)
        ));
    }
}

#[test]
fn rejects_world_name_over_the_text_limit() {
    use fastnbt::Value;
    let data = HashMap::from([
        ("LevelName".to_owned(), Value::String("世".repeat(400))),
        ("SpawnX".to_owned(), Value::Int(0)),
        ("SpawnY".to_owned(), Value::Int(64)),
        ("SpawnZ".to_owned(), Value::Int(0)),
    ]);
    let bytes =
        fastnbt::to_bytes(&HashMap::from([("Data".to_owned(), Value::Compound(data))])).unwrap();
    assert!(matches!(
        Fixture::new(&bytes).read(&NeverCancel),
        Err(CoreError::ResourceLimit)
    ));
}

#[test]
fn enforces_compressed_input_and_inflated_output_limits() {
    let oversized_input = vec![0_u8; LEVEL_INPUT_BYTES + 1];
    assert!(matches!(
        Fixture::new(&oversized_input).read(&NeverCancel),
        Err(CoreError::ResourceLimit)
    ));

    let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
    let block = [0_u8; 8192];
    for _ in 0..=(NBT_OUTPUT_BYTES / block.len()) {
        encoder.write_all(&block).unwrap();
    }
    let bomb = encoder.finish().unwrap();
    assert!(matches!(
        Fixture::new(&bomb).read(&NeverCancel),
        Err(CoreError::ResourceLimit)
    ));
}

struct AlwaysCancelled;
impl Cancellation for AlwaysCancelled {
    fn is_cancelled(&self) -> bool {
        true
    }
}

#[test]
fn cancellation_stops_before_parsing() {
    assert!(matches!(
        Fixture::new(&valid_nbt()).read(&AlwaysCancelled),
        Err(CoreError::Cancelled)
    ));
}

#[test]
fn rejects_excessive_nbt_nesting_as_a_resource_limit() {
    let mut bytes = vec![10, 0, 0];
    for _ in 0..65 {
        bytes.extend_from_slice(&[10, 0, 1, b'x']);
    }
    bytes.extend(std::iter::repeat_n(0, 66));
    assert!(matches!(
        Fixture::new(&bytes).read(&NeverCancel),
        Err(CoreError::ResourceLimit)
    ));
}

#[test]
fn public_errors_do_not_expose_absolute_paths() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../target/mcwe-core-tests")
        .canonicalize()
        .unwrap();
    let source = WorldSource::new(&root).unwrap();
    let message = read_world_info(&source, &NeverCancel)
        .unwrap_err()
        .to_string();
    assert!(!message.contains(root.to_string_lossy().as_ref()));
}
