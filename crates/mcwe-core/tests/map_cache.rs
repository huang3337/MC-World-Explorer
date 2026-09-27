#![cfg(windows)]

use fastnbt::Value;
use mcwe_core::{
    cancel::{Cancellation, NeverCancel},
    map::{
        load_map_tile_cached, load_map_tile_cached_progressive,
        load_map_tile_cached_progressive_focused, MapSessionCache, MapTileFocus, MapTileRequest,
    },
    CoreError, WorldSource,
};
use std::{
    collections::HashMap,
    fs,
    io::Write,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    thread,
    time::Duration,
};

const SECTOR: usize = 4096;

struct Fixture {
    root: PathBuf,
    region: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/mcwe-core-tests");
        fs::create_dir_all(&base).unwrap();
        let root = base.canonicalize().unwrap().join(format!(
            "map-cache-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        fs::create_dir(root.join("region")).unwrap();
        let region = root.join("region/r.0.0.mca");
        fs::write(&region, single_chunk_region()).unwrap();
        Self { root, region }
    }

    fn source(&self) -> WorldSource {
        WorldSource::new(&self.root).unwrap()
    }

    fn change_source_length(&self) {
        fs::OpenOptions::new()
            .append(true)
            .open(&self.region)
            .unwrap()
            .write_all(&vec![0; SECTOR])
            .unwrap();
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.region);
        let _ = fs::remove_dir(self.root.join("region"));
        let _ = fs::remove_dir(&self.root);
    }
}

fn single_chunk_region() -> Vec<u8> {
    let payload = chunk_bytes();
    let mut bytes = vec![0_u8; 3 * SECTOR];
    bytes[0..4].copy_from_slice(&[0, 0, 2, 1]);
    let length = u32::try_from(payload.len() + 1).unwrap();
    bytes[2 * SECTOR..2 * SECTOR + 4].copy_from_slice(&length.to_be_bytes());
    bytes[2 * SECTOR + 4] = 3;
    bytes[2 * SECTOR + 5..2 * SECTOR + 5 + payload.len()].copy_from_slice(&payload);
    bytes
}

fn chunk_bytes() -> Vec<u8> {
    let palette = Value::List(vec![Value::Compound(HashMap::from([(
        "Name".to_owned(),
        Value::String("minecraft:stone".to_owned()),
    )]))]);
    let section = Value::Compound(HashMap::from([
        ("Y".to_owned(), Value::Byte(0)),
        (
            "block_states".to_owned(),
            Value::Compound(HashMap::from([("palette".to_owned(), palette)])),
        ),
    ]));
    fastnbt::to_bytes(&HashMap::from([
        ("xPos".to_owned(), Value::Int(0)),
        ("zPos".to_owned(), Value::Int(0)),
        ("sections".to_owned(), Value::List(vec![section])),
    ]))
    .unwrap()
}

fn request(blocks_per_pixel: u8) -> MapTileRequest {
    MapTileRequest {
        tile_x: 0,
        tile_z: 0,
        blocks_per_pixel,
    }
}

struct SharedCancellation(Arc<AtomicBool>);

impl Cancellation for SharedCancellation {
    fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

struct CancelAfterChunkMisses<'a> {
    cache: &'a MapSessionCache,
    limit: u64,
}

impl Cancellation for CancelAfterChunkMisses<'_> {
    fn is_cancelled(&self) -> bool {
        self.cache.stats().chunk_misses >= self.limit
    }
}

#[test]
fn repeated_tile_hits_final_cache_and_zoom_change_reuses_chunks() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();

    let first = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    let second = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    assert!(Arc::ptr_eq(&first, &second));
    let before_zoom = cache.stats();
    assert_eq!(before_zoom.tile_hits, 1);
    assert_eq!(before_zoom.tile_misses, 1);

    let zoomed = load_map_tile_cached(&source, &cache, request(2), &NeverCancel).unwrap();
    assert_eq!(zoomed.successful_chunks, 1);
    let after_zoom = cache.stats();
    assert!(after_zoom.chunk_hits >= 256);
    assert_eq!(after_zoom.tile_misses, 2);
}

#[test]
fn cancelled_request_cannot_return_cached_final_tile() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    let cancelled = SharedCancellation(Arc::new(AtomicBool::new(true)));

    assert!(matches!(
        load_map_tile_cached(&source, &cache, request(1), &cancelled),
        Err(CoreError::Cancelled)
    ));
    assert_eq!(cache.stats().tile_hits, 0);
}

#[test]
fn changed_region_invalidates_tile_and_chunk_entries() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let first = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    fixture.change_source_length();
    let second = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();

    assert!(!Arc::ptr_eq(&first, &second));
    assert_eq!(cache.stats().tile_misses, 2);
    assert_eq!(second.successful_chunks, 1);
}

#[test]
fn independent_session_caches_never_share_entries() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let first_cache = MapSessionCache::default();
    let second_cache = MapSessionCache::default();
    load_map_tile_cached(&source, &first_cache, request(1), &NeverCancel).unwrap();
    load_map_tile_cached(&source, &second_cache, request(1), &NeverCancel).unwrap();

    assert_eq!(first_cache.stats().tile_misses, 1);
    assert_eq!(first_cache.stats().tile_hits, 0);
    assert_eq!(second_cache.stats().tile_misses, 1);
    assert_eq!(second_cache.stats().tile_hits, 0);
}

#[test]
fn interrupted_progress_does_not_cross_session_caches() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let first = MapSessionCache::default();
    let second = MapSessionCache::default();
    let cancelled = Arc::new(AtomicBool::new(false));
    let cancellation = SharedCancellation(Arc::clone(&cancelled));
    assert!(matches!(
        load_map_tile_cached_progressive(&source, &first, request(1), &cancellation, |_| {
            cancelled.store(true, Ordering::Release);
            Ok(())
        }),
        Err(CoreError::Cancelled)
    ));
    assert_eq!(first.stats().progress_entries, 1);
    assert_eq!(second.stats().progress_entries, 0);
    let tile = load_map_tile_cached(&source, &second, request(1), &NeverCancel).unwrap();
    assert_eq!(tile.successful_chunks, 1);
    assert_eq!(second.stats().progress_hits, 0);
    assert_eq!(second.stats().chunk_hits, 0);
}

#[test]
fn transient_sharing_failure_is_not_replaced_by_cached_success() {
    use std::os::windows::fs::OpenOptionsExt;

    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    let writer = fs::OpenOptions::new()
        .write(true)
        .share_mode(7)
        .open(&fixture.region)
        .unwrap();
    assert!(matches!(
        load_map_tile_cached(&source, &cache, request(1), &NeverCancel),
        Err(CoreError::InUse)
    ));
    drop(writer);
    assert_eq!(cache.stats().tile_hits, 0);
}

#[test]
fn progressive_generation_publishes_bounded_increasing_rectangle_patches() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let mut patches = Vec::new();
    let tile =
        load_map_tile_cached_progressive(&source, &cache, request(1), &NeverCancel, |patch| {
            if patches.is_empty() {
                thread::sleep(Duration::from_millis(110));
            }
            patches.push(patch);
            Ok(())
        })
        .unwrap();

    assert!(patches.len() >= 2);
    for (index, patch) in patches.iter().enumerate() {
        assert_eq!(patch.revision, index as u64 + 1);
        assert!(patch.completed_chunks < patch.total_chunks);
        assert_eq!(
            patch.colors.len(),
            usize::from(patch.width) * usize::from(patch.height)
        );
        assert_eq!(patch.colors.len(), patch.heights.len());
        assert_eq!(patch.colors.len(), patch.statuses.len());
        assert!(usize::from(patch.pixel_x) + usize::from(patch.width) <= 256);
        assert!(usize::from(patch.pixel_z) + usize::from(patch.height) <= 256);
        assert!(patch.statuses.iter().all(|status| *status <= 4));
    }
    assert_eq!(patches[0].completed_chunks, 1);
    assert_eq!((patches[0].width, patches[0].height), (17, 17));
    assert!(tile.statuses.iter().all(|status| *status <= 3));
}

#[test]
fn focused_generation_publishes_the_nearest_chunk_first() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let mut first_patch = None;

    load_map_tile_cached_progressive_focused(
        &source,
        &cache,
        request(1),
        MapTileFocus {
            block_x: 248.0,
            block_z: 248.0,
        },
        &NeverCancel,
        |patch| {
            if first_patch.is_none() {
                first_patch = Some(patch);
            }
            Ok(())
        },
    )
    .unwrap();

    let first = first_patch.unwrap();
    assert_eq!(first.completed_chunks, 1);
    assert_eq!((first.pixel_x, first.pixel_z), (240, 240));
    assert_eq!((first.width, first.height), (16, 16));
}

#[test]
fn cancelled_tile_keeps_completed_chunks_for_the_retry() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let cancelled = Arc::new(AtomicBool::new(false));
    let cancellation = SharedCancellation(Arc::clone(&cancelled));

    let result =
        load_map_tile_cached_progressive(&source, &cache, request(1), &cancellation, |_| {
            cancelled.store(true, Ordering::Release);
            Ok(())
        });

    assert!(matches!(result, Err(CoreError::Cancelled)));
    let after_cancel = cache.stats();
    assert_eq!(after_cancel.tile_entries, 0);
    assert!(after_cancel.chunk_entries >= 1);
    assert_eq!(after_cancel.progress_entries, 1);

    let retry = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    let after_retry = cache.stats();
    assert_eq!(retry.successful_chunks, 1);
    assert_eq!(after_retry.progress_hits, 1);
    assert_eq!(after_retry.chunk_misses, 256);
    assert_eq!(after_retry.tile_entries, 1);
    assert_eq!(after_retry.progress_entries, 0);
}

#[test]
fn interrupted_wide_tile_resumes_beyond_chunk_cache_capacity() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let cancellation = CancelAfterChunkMisses {
        cache: &cache,
        limit: 600,
    };

    assert!(matches!(
        load_map_tile_cached(&source, &cache, request(2), &cancellation),
        Err(CoreError::Cancelled)
    ));
    let interrupted = cache.stats();
    assert_eq!(interrupted.progress_entries, 1);
    assert!(interrupted.chunk_entries <= 512);
    assert!(interrupted.chunk_misses >= 600);

    let mut restored_chunks = None;
    let tile =
        load_map_tile_cached_progressive(&source, &cache, request(2), &NeverCancel, |patch| {
            if restored_chunks.is_none() {
                restored_chunks = Some(patch.completed_chunks);
            }
            Ok(())
        })
        .unwrap();
    let final_stats = cache.stats();
    assert_eq!(tile.successful_chunks, 1);
    assert_eq!(tile.missing_chunks, 1023);
    assert!(restored_chunks.is_some_and(|count| count >= 599));
    assert_eq!(final_stats.progress_hits, 1);
    assert!(
        final_stats.chunk_misses <= 1025,
        "completed chunks must not be processed twice"
    );
    assert_eq!(final_stats.progress_entries, 0);
    assert_eq!(final_stats.tile_entries, 1);
}

#[test]
fn source_change_after_cancellation_rejects_the_retained_chunk() {
    let fixture = Fixture::new();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let cancelled = Arc::new(AtomicBool::new(false));
    let cancellation = SharedCancellation(Arc::clone(&cancelled));

    let result =
        load_map_tile_cached_progressive(&source, &cache, request(1), &cancellation, |_| {
            cancelled.store(true, Ordering::Release);
            Ok(())
        });
    assert!(matches!(result, Err(CoreError::Cancelled)));
    let after_cancel = cache.stats();
    assert!(after_cancel.chunk_entries >= 1);
    assert_eq!(after_cancel.progress_entries, 1);

    fixture.change_source_length();
    let retry = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    let after_retry = cache.stats();
    assert_eq!(retry.successful_chunks, 1);
    assert_eq!(after_retry.progress_hits, 0);
    assert_eq!(after_retry.progress_entries, 0);
    assert_eq!(after_retry.chunk_hits, after_cancel.chunk_hits);
    assert!(after_retry.chunk_misses > after_cancel.chunk_misses);
}

#[test]
fn source_change_during_generation_does_not_keep_stale_chunk_entries() {
    let fixture = Fixture::new();
    fs::remove_file(&fixture.region).unwrap();
    let source = fixture.source();
    let cache = MapSessionCache::default();
    let mut created = false;

    let result =
        load_map_tile_cached_progressive(&source, &cache, request(1), &NeverCancel, |_| {
            if !created {
                fs::write(&fixture.region, single_chunk_region()).unwrap();
                created = true;
            }
            Ok(())
        });

    assert!(matches!(result, Err(CoreError::InUse)));
    assert_eq!(cache.stats().tile_entries, 0);
    assert_eq!(cache.stats().chunk_entries, 0);

    let retry = load_map_tile_cached(&source, &cache, request(1), &NeverCancel).unwrap();
    assert_eq!(retry.successful_chunks, 1);
}
