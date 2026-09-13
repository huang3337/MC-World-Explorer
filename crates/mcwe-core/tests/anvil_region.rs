#![cfg(windows)]

use flate2::{
    write::{GzEncoder, ZlibEncoder},
    Compression,
};
use mcwe_core::{
    anvil::{read_chunk_payload, ChunkCompression, ChunkCoordinate},
    cancel::{Cancellation, NeverCancel},
    CoreError, WorldSource,
};
use std::{
    fs,
    io::Write,
    path::PathBuf,
    sync::atomic::{AtomicU64, AtomicUsize, Ordering},
};

const SECTOR: usize = 4096;
const HEADER: usize = SECTOR * 2;

struct Fixture {
    root: PathBuf,
    files: Vec<PathBuf>,
}

impl Fixture {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/mcwe-core-tests");
        fs::create_dir_all(&base).unwrap();
        let root = base.canonicalize().unwrap().join(format!(
            "anvil-region-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        fs::create_dir(root.join("region")).unwrap();
        Self {
            root,
            files: Vec::new(),
        }
    }

    fn source(&self) -> WorldSource {
        WorldSource::new(&self.root).unwrap()
    }

    fn write_region(&mut self, rx: i32, rz: i32, bytes: &[u8]) -> PathBuf {
        let path = self.root.join(format!("region/r.{rx}.{rz}.mca"));
        fs::write(&path, bytes).unwrap();
        self.files.push(path.clone());
        path
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        for path in self.files.iter().rev() {
            let _ = fs::remove_file(path);
        }
        let _ = fs::remove_dir(self.root.join("region"));
        let _ = fs::remove_dir(&self.root);
    }
}

fn encode(raw: &[u8], compression: u8) -> Vec<u8> {
    match compression {
        1 => {
            let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
            encoder.write_all(raw).unwrap();
            encoder.finish().unwrap()
        }
        2 => {
            let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
            encoder.write_all(raw).unwrap();
            encoder.finish().unwrap()
        }
        _ => raw.to_vec(),
    }
}

fn region(
    slot: usize,
    sector_offset: u32,
    sector_count: u8,
    compression: u8,
    raw: &[u8],
) -> Vec<u8> {
    let payload = encode(raw, compression);
    let sectors = usize::try_from(sector_offset).unwrap() + usize::from(sector_count);
    let mut bytes = vec![0_u8; sectors.max(2) * SECTOR];
    set_location(&mut bytes, slot, sector_offset, sector_count);
    if sector_offset >= 2 && sector_count > 0 {
        let start = usize::try_from(sector_offset).unwrap() * SECTOR;
        let length = u32::try_from(payload.len() + 1).unwrap();
        bytes[start..start + 4].copy_from_slice(&length.to_be_bytes());
        bytes[start + 4] = compression;
        bytes[start + 5..start + 5 + payload.len()].copy_from_slice(&payload);
    }
    bytes
}

fn set_location(bytes: &mut [u8], slot: usize, sector_offset: u32, sector_count: u8) {
    let start = slot * 4;
    let encoded = sector_offset.to_be_bytes();
    bytes[start..start + 3].copy_from_slice(&encoded[1..]);
    bytes[start + 3] = sector_count;
}

fn overwrite_length(bytes: &mut [u8], sector_offset: usize, length: u32) {
    let start = sector_offset * SECTOR;
    bytes[start..start + 4].copy_from_slice(&length.to_be_bytes());
}

fn assert_region_error(bytes: &[u8], expected: fn(&CoreError) -> bool) {
    let mut fixture = Fixture::new();
    fixture.write_region(0, 0, bytes);
    let error = read_chunk_payload(
        &fixture.source(),
        ChunkCoordinate { x: 0, z: 0 },
        &NeverCancel,
    )
    .unwrap_err();
    assert!(expected(&error), "unexpected error: {error:?}");
}

#[test]
fn reads_negative_coordinate_from_the_expected_region_and_slot() {
    let coordinate = ChunkCoordinate { x: -1, z: -1 };
    assert_eq!(coordinate.region(), (-1, -1));
    assert_eq!(coordinate.local(), (31, 31));
    assert_eq!(coordinate.slot(), 1023);

    let mut fixture = Fixture::new();
    fixture.write_region(-1, -1, &region(1023, 2, 1, 3, b"negative"));
    let payload = read_chunk_payload(&fixture.source(), coordinate, &NeverCancel)
        .unwrap()
        .unwrap();
    assert_eq!(payload.compression, ChunkCompression::Uncompressed);
    assert_eq!(payload.bytes, b"negative");
}

#[test]
fn missing_region_and_empty_location_are_absent() {
    let fixture = Fixture::new();
    assert!(read_chunk_payload(
        &fixture.source(),
        ChunkCoordinate { x: 0, z: 0 },
        &NeverCancel,
    )
    .unwrap()
    .is_none());

    let mut fixture = Fixture::new();
    fixture.write_region(0, 0, &vec![0_u8; HEADER]);
    assert!(read_chunk_payload(
        &fixture.source(),
        ChunkCoordinate { x: 0, z: 0 },
        &NeverCancel,
    )
    .unwrap()
    .is_none());
}

#[test]
fn reads_gzip_zlib_and_uncompressed_payloads() {
    for (compression, expected) in [
        (1, ChunkCompression::Gzip),
        (2, ChunkCompression::Zlib),
        (3, ChunkCompression::Uncompressed),
    ] {
        let mut fixture = Fixture::new();
        fixture.write_region(0, 0, &region(0, 2, 1, compression, b"payload"));
        let payload = read_chunk_payload(
            &fixture.source(),
            ChunkCoordinate { x: 0, z: 0 },
            &NeverCancel,
        )
        .unwrap()
        .unwrap();
        assert_eq!(payload.compression, expected);
        assert_eq!(payload.bytes, b"payload");
    }
}

#[test]
fn rejects_invalid_header_locations_lengths_and_truncation() {
    let invalid = |error: &CoreError| matches!(error, CoreError::InvalidRegion);
    assert_region_error(&vec![0_u8; HEADER - 1], invalid);

    for (offset, count) in [(0, 1), (1, 1), (2, 0)] {
        let mut bytes = vec![0_u8; HEADER + SECTOR];
        set_location(&mut bytes, 0, offset, count);
        assert_region_error(&bytes, invalid);
    }

    let mut beyond_file = vec![0_u8; HEADER + SECTOR];
    set_location(&mut beyond_file, 0, 3, 1);
    assert_region_error(&beyond_file, invalid);

    for length in [0, (SECTOR - 3) as u32] {
        let mut bytes = region(0, 2, 1, 3, b"x");
        overwrite_length(&mut bytes, 2, length);
        assert_region_error(&bytes, invalid);
    }
}

#[test]
fn rejects_unknown_lz4_external_and_payload_beyond_allocated_sectors() {
    let unsupported = |error: &CoreError| matches!(error, CoreError::UnsupportedCompression);
    for compression in [4, 0x82] {
        assert_region_error(&region(0, 2, 1, compression, b"payload"), unsupported);
    }

    let mut bytes = vec![0_u8; HEADER + 257 * SECTOR];
    set_location(&mut bytes, 0, 2, 255);
    overwrite_length(&mut bytes, 2, (1024 * 1024 + 2) as u32);
    assert_region_error(&bytes, |error| matches!(error, CoreError::InvalidRegion));
}

#[test]
fn ignores_unrequested_location_entries() {
    let coordinate = ChunkCoordinate { x: 1, z: 0 };
    let mut bytes = region(coordinate.slot(), 2, 1, 3, b"requested-only");
    set_location(&mut bytes, 0, 1, 1);
    let mut fixture = Fixture::new();
    fixture.write_region(0, 0, &bytes);
    let payload = read_chunk_payload(&fixture.source(), coordinate, &NeverCancel)
        .unwrap()
        .unwrap();
    assert_eq!(payload.bytes, b"requested-only");
}

struct CancelOnSecondCheck(AtomicUsize);

impl Cancellation for CancelOnSecondCheck {
    fn is_cancelled(&self) -> bool {
        self.0.fetch_add(1, Ordering::SeqCst) >= 1
    }
}

#[test]
fn cancellation_after_file_read_releases_the_region_handle() {
    let mut fixture = Fixture::new();
    let path = fixture.write_region(0, 0, &region(0, 2, 1, 3, b"payload"));
    let cancel = CancelOnSecondCheck(AtomicUsize::new(0));
    assert!(matches!(
        read_chunk_payload(&fixture.source(), ChunkCoordinate { x: 0, z: 0 }, &cancel,),
        Err(CoreError::Cancelled)
    ));
    fs::OpenOptions::new().write(true).open(path).unwrap();
}

#[test]
fn incompatible_existing_writer_is_reported_and_normal_read_releases_file() {
    use std::os::windows::fs::OpenOptionsExt;

    let mut fixture = Fixture::new();
    let path = fixture.write_region(0, 0, &region(0, 2, 1, 3, b"payload"));
    let writer = fs::OpenOptions::new()
        .write(true)
        .share_mode(7)
        .open(&path)
        .unwrap();
    assert!(matches!(
        read_chunk_payload(
            &fixture.source(),
            ChunkCoordinate { x: 0, z: 0 },
            &NeverCancel,
        ),
        Err(CoreError::InUse)
    ));
    drop(writer);

    read_chunk_payload(
        &fixture.source(),
        ChunkCoordinate { x: 0, z: 0 },
        &NeverCancel,
    )
    .unwrap();
    fs::OpenOptions::new().write(true).open(path).unwrap();
}
