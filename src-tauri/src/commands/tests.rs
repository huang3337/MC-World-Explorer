use super::configure;
use crate::{tasks::TaskState, world_session::WorldSessionState};
use mcwe_core::cancel::Cancellation;
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};
use tauri::{
    ipc::{CallbackFn, InvokeBody, InvokeResponseBody},
    test::{get_ipc_response, mock_builder, mock_context, noop_assets, MockRuntime, INVOKE_KEY},
    webview::InvokeRequest,
    Manager,
};

const LOCAL_URL: &str = "http://tauri.localhost";

fn request(command: &str, body: Value) -> InvokeRequest {
    request_at(command, body, LOCAL_URL)
}

fn request_at(command: &str, body: Value, url: &str) -> InvokeRequest {
    InvokeRequest {
        cmd: command.into(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        url: url.parse().unwrap(),
        body: InvokeBody::Json(body),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.into(),
    }
}

fn app() -> tauri::App<MockRuntime> {
    configure(
        mock_builder(),
        Err(std::io::Error::other("portable paths are not used")),
    )
    .build(mock_context(noop_assets()))
    .unwrap()
}

fn main_window(app: &tauri::App<MockRuntime>) -> tauri::WebviewWindow<MockRuntime> {
    tauri::WebviewWindowBuilder::new(app, "main", Default::default())
        .build()
        .unwrap()
}

struct WorldFixture {
    root: PathBuf,
}

impl WorldFixture {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/command-tests");
        fs::create_dir_all(&base).unwrap();
        let root = base.canonicalize().unwrap().join(format!(
            "world-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        fs::create_dir(root.join("region")).unwrap();
        fs::write(root.join("level.dat"), valid_level()).unwrap();
        fs::write(root.join("region/r.0.0.mca"), region_with_stone()).unwrap();
        Self { root }
    }

    fn path(&self) -> &Path {
        &self.root
    }

    fn add_far_chunk(&self) {
        fs::write(
            self.root.join("region/r.2.0.mca"),
            region_with_stone_at(64, 0),
        )
        .unwrap();
    }
}

impl Drop for WorldFixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(self.root.join("region/r.2.0.mca"));
        let _ = fs::remove_file(self.root.join("region/r.0.0.mca"));
        let _ = fs::remove_file(self.root.join("level.dat"));
        let _ = fs::remove_dir(self.root.join("region"));
        let _ = fs::remove_dir(&self.root);
    }
}

fn named(bytes: &mut Vec<u8>, tag: u8, name: &str) {
    bytes.push(tag);
    bytes.extend_from_slice(&(name.len() as u16).to_be_bytes());
    bytes.extend_from_slice(name.as_bytes());
}

fn nbt_string(bytes: &mut Vec<u8>, value: &str) {
    bytes.extend_from_slice(&(value.len() as u16).to_be_bytes());
    bytes.extend_from_slice(value.as_bytes());
}

fn valid_level() -> Vec<u8> {
    let mut bytes = vec![10, 0, 0];
    named(&mut bytes, 10, "Data");
    named(&mut bytes, 3, "DataVersion");
    bytes.extend_from_slice(&3955_i32.to_be_bytes());
    named(&mut bytes, 8, "LevelName");
    nbt_string(&mut bytes, "IPC合成世界");
    for (name, value) in [("SpawnX", 0_i32), ("SpawnY", 64), ("SpawnZ", 0)] {
        named(&mut bytes, 3, name);
        bytes.extend_from_slice(&value.to_be_bytes());
    }
    bytes.extend_from_slice(&[0, 0]);
    bytes
}

fn stone_chunk_at(chunk_x: i32, chunk_z: i32) -> Vec<u8> {
    let mut bytes = vec![10, 0, 0];
    for (name, coordinate) in [("xPos", chunk_x), ("zPos", chunk_z)] {
        named(&mut bytes, 3, name);
        bytes.extend_from_slice(&coordinate.to_be_bytes());
    }
    named(&mut bytes, 9, "sections");
    bytes.push(10);
    bytes.extend_from_slice(&1_i32.to_be_bytes());
    named(&mut bytes, 1, "Y");
    bytes.push(0);
    named(&mut bytes, 10, "block_states");
    named(&mut bytes, 9, "palette");
    bytes.push(10);
    bytes.extend_from_slice(&1_i32.to_be_bytes());
    named(&mut bytes, 8, "Name");
    nbt_string(&mut bytes, "minecraft:stone");
    bytes.extend_from_slice(&[0, 0, 0, 0]);
    bytes
}

fn region_with_stone() -> Vec<u8> {
    region_with_stone_at(0, 0)
}

fn region_with_stone_at(chunk_x: i32, chunk_z: i32) -> Vec<u8> {
    const SECTOR: usize = 4096;
    let payload = stone_chunk_at(chunk_x, chunk_z);
    let mut bytes = vec![0_u8; SECTOR * 3];
    bytes[0..4].copy_from_slice(&[0, 0, 2, 1]);
    let start = SECTOR * 2;
    bytes[start..start + 4]
        .copy_from_slice(&u32::try_from(payload.len() + 1).unwrap().to_be_bytes());
    bytes[start + 4] = 3;
    bytes[start + 5..start + 5 + payload.len()].copy_from_slice(&payload);
    bytes
}

fn select_world(app: &tauri::App<MockRuntime>, fixture: &WorldFixture) -> u64 {
    app.state::<WorldSessionState>()
        .replace_candidate(fixture.path().to_path_buf())
        .unwrap()
        .session_id
}

fn assert_raw(response: InvokeResponseBody, kind: u16) -> Vec<u8> {
    let InvokeResponseBody::Raw(bytes) = response else {
        panic!("expected raw IPC response");
    };
    assert_eq!(&bytes[0..4], b"MCWE");
    assert_eq!(u16::from_le_bytes(bytes[6..8].try_into().unwrap()), kind);
    assert_eq!(
        u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize,
        bytes.len()
    );
    bytes
}

#[test]
fn surface_and_mesh_commands_return_raw_protocol_payloads() {
    let fixture = WorldFixture::new();
    let app = app();
    let session_id = select_world(&app, &fixture);
    let window = main_window(&app);

    let surface = get_ipc_response(
        &window,
        request("load_surface", json!({"sessionId": session_id})),
    )
    .unwrap();
    let surface = assert_raw(surface, 1);
    assert!(String::from_utf8_lossy(&surface).contains(r#""sessionId":1"#));

    let tile = get_ipc_response(
        &window,
        request(
            "load_map_tile",
            json!({"sessionId": session_id, "tileX": 0, "tileZ": 0, "blocksPerPixel": 1}),
        ),
    )
    .unwrap();
    let tile = assert_raw(tile, 3);
    assert!(String::from_utf8_lossy(&tile).contains(r#""blocksPerPixel":1"#));
    let status_offset = u32::from_le_bytes(tile[68..72].try_into().unwrap()) as usize;
    assert_eq!(tile[status_offset], 0);
    assert_eq!(tile[status_offset + 16], 1);

    let mesh = get_ipc_response(
        &window,
        request(
            "build_mesh_for_selection",
            json!({
                "sessionId": session_id,
                "chunkRect": {"minX": 0, "minZ": 0, "width": 1, "depth": 1}
            }),
        ),
    )
    .unwrap();
    let mesh = assert_raw(mesh, 2);
    assert!(String::from_utf8_lossy(&mesh).contains(r#""sessionId":1"#));

    app.state::<WorldSessionState>().clear_for_test();
    drop(window);
    drop(app);
}

#[test]
fn mesh_selection_can_use_a_loaded_chunk_outside_the_legacy_surface() {
    let fixture = WorldFixture::new();
    fixture.add_far_chunk();
    let app = app();
    let session_id = select_world(&app, &fixture);
    let window = main_window(&app);

    let response = get_ipc_response(
        &window,
        request(
            "build_mesh_for_selection",
            json!({
                "sessionId": session_id,
                "chunkRect": {"minX": 64, "minZ": 0, "width": 1, "depth": 1}
            }),
        ),
    )
    .unwrap();
    let mesh = assert_raw(response, 2);
    assert!(String::from_utf8_lossy(&mesh).contains(r#""minX":64"#));

    app.state::<WorldSessionState>().clear_for_test();
    drop(window);
    drop(app);
}

#[test]
fn map_viewport_starts_without_surface_and_streams_until_ended() {
    let fixture = WorldFixture::new();
    let app = app();
    let session_id = select_world(&app, &fixture);
    let window = main_window(&app);
    let summary = get_ipc_response(
        &window,
        request(
            "start_map_viewport",
            json!({
                "sessionId": session_id,
                "centerX": 128.0,
                "centerZ": 128.0,
                "viewportWidth": 1,
                "viewportHeight": 1,
                "blocksPerPixel": 1,
                "displayBlocksPerPixel": 1.0
            }),
        ),
    )
    .unwrap()
    .deserialize::<Value>()
    .unwrap();
    assert_eq!(summary["sessionId"], session_id);
    assert_eq!(summary["targetCount"], 1);
    assert_eq!(summary["targets"][0]["tileX"], 0);
    let viewport_id = summary["viewportTaskId"].as_u64().unwrap();
    let mut ended = false;
    for _ in 0..16 {
        let bytes = assert_raw(
            get_ipc_response(
                &window,
                request(
                    "next_map_viewport_batch",
                    json!({"sessionId": session_id, "viewportTaskId": viewport_id}),
                ),
            )
            .unwrap(),
            4,
        );
        let metadata_offset = u32::from_le_bytes(bytes[16..20].try_into().unwrap()) as usize;
        let metadata: Value = serde_json::from_slice(&bytes[metadata_offset..]).unwrap();
        if metadata["state"] == "ended" {
            assert_eq!(metadata["terminalItemCount"], 1);
            ended = true;
            break;
        }
    }
    assert!(ended);
    let session = app.state::<WorldSessionState>().get(session_id).unwrap();
    let deadline = Instant::now() + Duration::from_secs(2);
    while session.map_cache.stats().tile_entries <= 1 && Instant::now() < deadline {
        std::thread::yield_now();
    }
    assert!(session.map_cache.stats().tile_entries > 1);
    let repeated = assert_raw(
        get_ipc_response(
            &window,
            request(
                "next_map_viewport_batch",
                json!({"sessionId": session_id, "viewportTaskId": viewport_id}),
            ),
        )
        .unwrap(),
        4,
    );
    let metadata_offset = u32::from_le_bytes(repeated[16..20].try_into().unwrap()) as usize;
    let metadata: Value = serde_json::from_slice(&repeated[metadata_offset..]).unwrap();
    assert_eq!(metadata["state"], "ended");
    assert_eq!(metadata["itemCount"], 0);
    app.state::<WorldSessionState>().clear_for_test();
}

#[test]
fn cancel_command_is_json_and_idempotent() {
    let app = app();
    let window = main_window(&app);
    let tasks = app.state::<TaskState>();
    let receipt = tasks
        .submit::<(), _>(1, |worker| loop {
            worker.check()?;
            std::thread::yield_now();
        })
        .unwrap();

    let cancelled = get_ipc_response(&window, request("cancel_active_task", json!({})))
        .unwrap()
        .deserialize::<Value>()
        .unwrap();
    assert_eq!(cancelled, json!({"cancelled": true}));
    assert!(receipt.wait().is_err());
    let repeated = get_ipc_response(&window, request("cancel_active_task", json!({})))
        .unwrap()
        .deserialize::<Value>()
        .unwrap();
    assert_eq!(repeated, json!({"cancelled": false}));
}

#[test]
fn old_session_ids_are_errors_instead_of_empty_raw_successes() {
    let first = WorldFixture::new();
    let second = WorldFixture::new();
    let app = app();
    let old_session_id = select_world(&app, &first);
    assert_eq!(select_world(&app, &second), old_session_id + 1);
    let window = main_window(&app);

    for (command, body) in [
        ("load_surface", json!({"sessionId": old_session_id})),
        (
            "build_mesh_for_selection",
            json!({
                "sessionId": old_session_id,
                "chunkRect": {"minX": 0, "minZ": 0, "width": 1, "depth": 1}
            }),
        ),
        (
            "load_map_tile",
            json!({"sessionId": old_session_id, "tileX": 0, "tileZ": 0, "blocksPerPixel": 1}),
        ),
        (
            "start_map_viewport",
            json!({"sessionId": old_session_id, "centerX": 0, "centerZ": 0, "viewportWidth": 800, "viewportHeight": 520, "blocksPerPixel": 1, "displayBlocksPerPixel": 1.0}),
        ),
        (
            "next_map_viewport_batch",
            json!({"sessionId": old_session_id, "viewportTaskId": 1}),
        ),
    ] {
        let error = get_ipc_response(&window, request(command, body)).unwrap_err();
        assert_eq!(error["code"], "SESSION_OUTDATED", "{command}");
    }

    app.state::<WorldSessionState>().clear_for_test();
    drop(window);
    drop(app);
}

#[test]
fn strict_arguments_reject_extra_outer_nested_and_raw_fields() {
    let app = app();
    let window = main_window(&app);
    for (command, body) in [
        (
            "load_surface",
            json!({"sessionId": 1, "path": "C:\\untrusted"}),
        ),
        (
            "build_mesh_for_selection",
            json!({
                "sessionId": 1,
                "chunkRect": {"minX": 0, "minZ": 0, "width": 1, "depth": 1},
                "path": "C:\\untrusted"
            }),
        ),
        ("cancel_active_task", json!({"taskId": 999})),
        ("scan_world_directory", json!({"path": "C:\\untrusted"})),
        (
            "open_discovered_world",
            json!({"worldId": 1, "path": "C:\\untrusted"}),
        ),
        (
            "load_map_tile",
            json!({"sessionId": 1, "tileX": 0, "tileZ": 0, "blocksPerPixel": 1, "path": "C:\\untrusted"}),
        ),
        (
            "start_map_viewport",
            json!({"sessionId": 1, "centerX": 0, "centerZ": 0, "viewportWidth": 800, "viewportHeight": 520, "blocksPerPixel": 1, "displayBlocksPerPixel": 1.0, "path": "C:\\untrusted"}),
        ),
        (
            "next_map_viewport_batch",
            json!({"sessionId": 1, "viewportTaskId": 1, "path": "C:\\untrusted"}),
        ),
        (
            "cancel_map_viewport",
            json!({"sessionId": 1, "viewportTaskId": 1, "path": "C:\\untrusted"}),
        ),
    ] {
        let error = get_ipc_response(&window, request(command, body)).unwrap_err();
        assert_eq!(error["code"], "INVALID_ARGUMENTS", "{command}");
    }

    let nested = get_ipc_response(
        &window,
        request(
            "build_mesh_for_selection",
            json!({
                "sessionId": 1,
                "chunkRect": {"minX": 0, "minZ": 0, "width": 1, "depth": 1, "path": "C:\\untrusted"}
            }),
        ),
    );
    assert!(nested.is_err());

    let raw = InvokeRequest {
        cmd: "cancel_active_task".into(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        url: LOCAL_URL.parse().unwrap(),
        body: InvokeBody::Raw(vec![]),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.into(),
    };
    let error = get_ipc_response(&window, raw).unwrap_err();
    assert_eq!(error["code"], "INVALID_ARGUMENTS");
}

#[test]
fn domain_failures_are_json_errors_not_empty_raw_successes() {
    let app = app();
    let window = main_window(&app);
    let surface =
        get_ipc_response(&window, request("load_surface", json!({"sessionId": 1}))).unwrap_err();
    assert_eq!(surface["code"], "NO_ACTIVE_WORLD");
    let tile = get_ipc_response(
        &window,
        request(
            "load_map_tile",
            json!({"sessionId": 1, "tileX": 0, "tileZ": 0, "blocksPerPixel": 1}),
        ),
    )
    .unwrap_err();
    assert_eq!(tile["code"], "NO_ACTIVE_WORLD");
    let mesh = get_ipc_response(
        &window,
        request(
            "build_mesh_for_selection",
            json!({
                "sessionId": 1,
                "chunkRect": {"minX": 0, "minZ": 0, "width": 1, "depth": 1}
            }),
        ),
    )
    .unwrap_err();
    assert_eq!(mesh["code"], "NO_ACTIVE_WORLD");
    for (command, body) in [
        (
            "start_map_viewport",
            json!({"sessionId": 1, "centerX": 0, "centerZ": 0, "viewportWidth": 800, "viewportHeight": 520, "blocksPerPixel": 1, "displayBlocksPerPixel": 1.0}),
        ),
        (
            "next_map_viewport_batch",
            json!({"sessionId": 1, "viewportTaskId": 1}),
        ),
    ] {
        let error = get_ipc_response(&window, request(command, body)).unwrap_err();
        assert_eq!(error["code"], "NO_ACTIVE_WORLD", "{command}");
    }
}

#[test]
fn map_viewport_cancellation_is_idempotent_and_validates_both_ids() {
    let app = app();
    let window = main_window(&app);
    let tasks = app.state::<TaskState>();
    let viewport_id = tasks.begin_map_viewport(1, &HashSet::new()).unwrap();
    tasks.track_map_viewport(1, viewport_id, 1, Vec::new());
    let poll = tasks.begin_map_poll(1, viewport_id).unwrap();
    for _ in 0..2 {
        let response = get_ipc_response(
            &window,
            request(
                "cancel_map_viewport",
                json!({"sessionId": 1, "viewportTaskId": viewport_id}),
            ),
        )
        .unwrap();
        assert!(matches!(response, InvokeResponseBody::Json(value) if value == "null"));
    }
    assert!(matches!(
        poll.wait(Duration::ZERO),
        Err(crate::tasks::MapPollError::Outdated)
    ));
    for body in [
        json!({"sessionId": 0, "viewportTaskId": 1}),
        json!({"sessionId": 1, "viewportTaskId": 0}),
        json!({"sessionId": 9_007_199_254_740_992_u64, "viewportTaskId": 1}),
    ] {
        let error = get_ipc_response(&window, request("cancel_map_viewport", body)).unwrap_err();
        assert_eq!(error["code"], "INVALID_ARGUMENTS");
    }
}

#[test]
fn every_business_command_rejects_other_windows_and_remote_origins() {
    let app = app();
    let other = tauri::WebviewWindowBuilder::new(&app, "other", Default::default())
        .build()
        .unwrap();
    let main = main_window(&app);
    let cases = [
        ("select_world", json!({})),
        ("scan_world_directory", json!({})),
        ("open_discovered_world", json!({"worldId": 1})),
        ("load_surface", json!({"sessionId": 1})),
        (
            "load_map_tile",
            json!({"sessionId": 1, "tileX": 0, "tileZ": 0, "blocksPerPixel": 1}),
        ),
        (
            "start_map_viewport",
            json!({"sessionId": 1, "centerX": 0, "centerZ": 0, "viewportWidth": 800, "viewportHeight": 520, "blocksPerPixel": 1, "displayBlocksPerPixel": 1.0}),
        ),
        (
            "next_map_viewport_batch",
            json!({"sessionId": 1, "viewportTaskId": 1}),
        ),
        (
            "cancel_map_viewport",
            json!({"sessionId": 1, "viewportTaskId": 1}),
        ),
        (
            "build_mesh_for_selection",
            json!({
                "sessionId": 1,
                "chunkRect": {"minX": 0, "minZ": 0, "width": 1, "depth": 1}
            }),
        ),
        ("cancel_active_task", json!({})),
    ];
    for (command, body) in cases {
        let error = get_ipc_response(&other, request(command, body.clone())).unwrap_err();
        assert_eq!(error["code"], "PERMISSION_DENIED", "{command}");
        assert!(get_ipc_response(&main, request_at(command, body, "https://example.com")).is_err());
    }
}

#[test]
fn command_surface_exposes_no_generic_file_api_or_capability_expansion() {
    let app = app();
    let window = main_window(&app);
    for command in ["read_file", "read_dir", "read_world", "write_file"] {
        assert!(get_ipc_response(&window, request(command, json!({}))).is_err());
    }

    let capability: Value =
        serde_json::from_str(include_str!("../../capabilities/main.json")).unwrap();
    assert_eq!(capability["windows"], json!(["main"]));
    assert_eq!(capability["permissions"], json!(["core:default"]));
    assert!(capability.to_string().find("fs:").is_none());
}
