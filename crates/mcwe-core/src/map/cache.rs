use super::{MapTile, MapTileRequest, TileProgress};
use crate::{anvil::ChunkCoordinate, chunk::DecodedChunk, surface::SurfaceCell, SourceFileState};
use std::{
    collections::{HashMap, VecDeque},
    hash::Hash,
    mem::size_of,
    sync::{Arc, Mutex, MutexGuard},
};

pub const CHUNK_CACHE_MAX_ENTRIES: usize = 512;
pub const CHUNK_CACHE_MAX_BYTES: usize = 128 * 1024 * 1024;
pub const TILE_CACHE_MAX_ENTRIES: usize = 128;
pub const TILE_CACHE_MAX_BYTES: usize = 96 * 1024 * 1024;
pub const PROGRESS_CACHE_MAX_ENTRIES: usize = 8;
pub const PROGRESS_CACHE_MAX_BYTES: usize = 32 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MapCacheScope {
    dimension: u8,
    layer: u8,
    render_version: u32,
}

impl MapCacheScope {
    pub const OVERWORLD_SURFACE_V1: Self = Self {
        dimension: 0,
        layer: 0,
        render_version: 1,
    };
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RegionSourceState {
    pub region_x: i32,
    pub region_z: i32,
    pub file: SourceFileState,
}

#[derive(Debug)]
pub(crate) struct ChunkMapData {
    pub decoded: Arc<DecodedChunk>,
    pub surface: Arc<[SurfaceCell]>,
}

impl ChunkMapData {
    fn storage_budget_bytes(&self) -> usize {
        self.decoded.storage_budget_bytes()
            + self.surface.len() * size_of::<SurfaceCell>()
            + size_of::<Self>()
            + 128
    }
}

#[derive(Debug, Clone)]
pub(crate) enum ChunkMapOutcome {
    Ready(Arc<ChunkMapData>),
    Missing,
    Unsupported,
    Corrupt,
}

impl ChunkMapOutcome {
    fn storage_budget_bytes(&self) -> usize {
        match self {
            Self::Ready(data) => data.storage_budget_bytes(),
            Self::Missing | Self::Unsupported | Self::Corrupt => 64,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct MapCacheStats {
    pub chunk_hits: u64,
    pub chunk_misses: u64,
    pub tile_hits: u64,
    pub tile_misses: u64,
    pub progress_hits: u64,
    pub progress_misses: u64,
    pub chunk_entries: usize,
    pub chunk_bytes: usize,
    pub tile_entries: usize,
    pub tile_bytes: usize,
    pub progress_entries: usize,
    pub progress_bytes: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct ChunkKey {
    scope: MapCacheScope,
    coordinate: ChunkCoordinate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct TileKey {
    scope: MapCacheScope,
    request: MapTileRequest,
}

struct CacheEntry<V, S> {
    value: V,
    source: S,
    bytes: usize,
}

struct WeightedLru<K, V, S> {
    entries: HashMap<K, CacheEntry<V, S>>,
    order: VecDeque<K>,
    bytes: usize,
    max_entries: usize,
    max_bytes: usize,
}

impl<K, V, S> WeightedLru<K, V, S>
where
    K: Copy + Eq + Hash,
    V: Clone,
    S: PartialEq,
{
    fn new(max_entries: usize, max_bytes: usize) -> Self {
        Self {
            entries: HashMap::new(),
            order: VecDeque::new(),
            bytes: 0,
            max_entries,
            max_bytes,
        }
    }

    fn get(&mut self, key: K, source: &S) -> Option<V> {
        if self
            .entries
            .get(&key)
            .is_some_and(|entry| &entry.source != source)
        {
            self.remove(key);
            return None;
        }
        let value = self.entries.get(&key)?.value.clone();
        self.touch(key);
        Some(value)
    }

    fn take(&mut self, key: K, source: &S) -> Option<V> {
        if self
            .entries
            .get(&key)
            .is_some_and(|entry| &entry.source != source)
        {
            self.remove(key);
            return None;
        }
        let value = self.entries.get(&key)?.value.clone();
        self.remove(key);
        Some(value)
    }

    fn insert(&mut self, key: K, source: S, value: V, bytes: usize) -> bool {
        self.remove(key);
        if bytes > self.max_bytes {
            return false;
        }
        self.bytes += bytes;
        self.entries.insert(
            key,
            CacheEntry {
                value,
                source,
                bytes,
            },
        );
        self.order.push_back(key);
        while self.entries.len() > self.max_entries || self.bytes > self.max_bytes {
            let Some(eldest) = self.order.pop_front() else {
                break;
            };
            if let Some(entry) = self.entries.remove(&eldest) {
                self.bytes -= entry.bytes;
            }
        }
        self.entries.contains_key(&key)
    }

    fn remove(&mut self, key: K) {
        if let Some(entry) = self.entries.remove(&key) {
            self.bytes -= entry.bytes;
        }
        if let Some(index) = self.order.iter().position(|candidate| *candidate == key) {
            self.order.remove(index);
        }
    }

    fn touch(&mut self, key: K) {
        if let Some(index) = self.order.iter().position(|candidate| *candidate == key) {
            self.order.remove(index);
        }
        self.order.push_back(key);
    }

    fn clear(&mut self) {
        self.entries.clear();
        self.order.clear();
        self.bytes = 0;
    }
}

struct CacheState {
    chunks: WeightedLru<ChunkKey, ChunkMapOutcome, RegionSourceState>,
    tiles: WeightedLru<TileKey, Arc<MapTile>, Vec<RegionSourceState>>,
    progress: WeightedLru<TileKey, Arc<TileProgress>, Vec<RegionSourceState>>,
    chunk_hits: u64,
    chunk_misses: u64,
    tile_hits: u64,
    tile_misses: u64,
    progress_hits: u64,
    progress_misses: u64,
}

pub struct MapSessionCache {
    state: Mutex<CacheState>,
}

impl Default for MapSessionCache {
    fn default() -> Self {
        Self::with_limits(
            CHUNK_CACHE_MAX_ENTRIES,
            CHUNK_CACHE_MAX_BYTES,
            TILE_CACHE_MAX_ENTRIES,
            TILE_CACHE_MAX_BYTES,
        )
    }
}

impl MapSessionCache {
    fn with_limits(
        chunk_entries: usize,
        chunk_bytes: usize,
        tile_entries: usize,
        tile_bytes: usize,
    ) -> Self {
        Self {
            state: Mutex::new(CacheState {
                chunks: WeightedLru::new(chunk_entries, chunk_bytes),
                tiles: WeightedLru::new(tile_entries, tile_bytes),
                progress: WeightedLru::new(PROGRESS_CACHE_MAX_ENTRIES, PROGRESS_CACHE_MAX_BYTES),
                chunk_hits: 0,
                chunk_misses: 0,
                tile_hits: 0,
                tile_misses: 0,
                progress_hits: 0,
                progress_misses: 0,
            }),
        }
    }

    pub(crate) fn get_chunk(
        &self,
        scope: MapCacheScope,
        coordinate: ChunkCoordinate,
        source: &RegionSourceState,
    ) -> Option<ChunkMapOutcome> {
        let mut state = lock(&self.state);
        let result = state.chunks.get(ChunkKey { scope, coordinate }, source);
        if result.is_some() {
            state.chunk_hits += 1;
        } else {
            state.chunk_misses += 1;
        }
        result
    }

    pub(crate) fn put_chunk(
        &self,
        scope: MapCacheScope,
        coordinate: ChunkCoordinate,
        source: RegionSourceState,
        value: ChunkMapOutcome,
    ) -> bool {
        let bytes = value.storage_budget_bytes();
        lock(&self.state)
            .chunks
            .insert(ChunkKey { scope, coordinate }, source, value, bytes)
    }

    pub(crate) fn invalidate_region_source(
        &self,
        scope: MapCacheScope,
        source: &RegionSourceState,
    ) {
        let mut state = lock(&self.state);
        let stale = state
            .chunks
            .entries
            .iter()
            .filter_map(|(key, entry)| {
                (key.scope == scope && &entry.source == source).then_some(*key)
            })
            .collect::<Vec<_>>();
        for key in stale {
            state.chunks.remove(key);
        }
        let stale_progress = state
            .progress
            .entries
            .iter()
            .filter_map(|(key, entry)| {
                (key.scope == scope && entry.source.contains(source)).then_some(*key)
            })
            .collect::<Vec<_>>();
        for key in stale_progress {
            state.progress.remove(key);
        }
    }

    pub(crate) fn get_tile(
        &self,
        scope: MapCacheScope,
        request: MapTileRequest,
        sources: &[RegionSourceState],
    ) -> Option<Arc<MapTile>> {
        let mut state = lock(&self.state);
        let result = state
            .tiles
            .get(TileKey { scope, request }, &sources.to_vec());
        if result.is_some() {
            state.tile_hits += 1;
        } else {
            state.tile_misses += 1;
        }
        result
    }

    pub(crate) fn put_tile(
        &self,
        scope: MapCacheScope,
        request: MapTileRequest,
        sources: Vec<RegionSourceState>,
        tile: Arc<MapTile>,
    ) -> bool {
        let bytes = map_tile_storage_budget_bytes(&tile);
        let mut state = lock(&self.state);
        let key = TileKey { scope, request };
        state.progress.remove(key);
        state.tiles.insert(key, sources, tile, bytes)
    }

    pub(crate) fn take_progress(
        &self,
        scope: MapCacheScope,
        request: MapTileRequest,
        sources: &[RegionSourceState],
    ) -> Option<TileProgress> {
        let mut state = lock(&self.state);
        let result = state
            .progress
            .take(TileKey { scope, request }, &sources.to_vec());
        if result.is_some() {
            state.progress_hits += 1;
        } else {
            state.progress_misses += 1;
        }
        result.map(|progress| Arc::try_unwrap(progress).unwrap_or_else(|shared| (*shared).clone()))
    }

    pub(crate) fn put_progress(
        &self,
        scope: MapCacheScope,
        request: MapTileRequest,
        sources: Vec<RegionSourceState>,
        progress: TileProgress,
    ) -> bool {
        let bytes = progress.storage_budget_bytes();
        lock(&self.state).progress.insert(
            TileKey { scope, request },
            sources,
            Arc::new(progress),
            bytes,
        )
    }

    pub fn clear(&self) {
        let mut state = lock(&self.state);
        state.chunks.clear();
        state.tiles.clear();
        state.progress.clear();
    }

    pub fn stats(&self) -> MapCacheStats {
        let state = lock(&self.state);
        MapCacheStats {
            chunk_hits: state.chunk_hits,
            chunk_misses: state.chunk_misses,
            tile_hits: state.tile_hits,
            tile_misses: state.tile_misses,
            progress_hits: state.progress_hits,
            progress_misses: state.progress_misses,
            chunk_entries: state.chunks.entries.len(),
            chunk_bytes: state.chunks.bytes,
            tile_entries: state.tiles.entries.len(),
            tile_bytes: state.tiles.bytes,
            progress_entries: state.progress.entries.len(),
            progress_bytes: state.progress.bytes,
        }
    }
}

fn map_tile_storage_budget_bytes(tile: &MapTile) -> usize {
    tile.colors.capacity() * size_of::<u32>()
        + tile.heights.capacity() * size_of::<i32>()
        + tile.statuses.capacity() * size_of::<u8>()
        + size_of::<MapTile>()
        + 128
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::map::TILE_CELLS;
    use std::time::SystemTime;

    fn source(version: u64) -> RegionSourceState {
        RegionSourceState {
            region_x: 0,
            region_z: 0,
            file: SourceFileState::File {
                len: version,
                modified: SystemTime::UNIX_EPOCH,
            },
        }
    }

    fn tile(request: MapTileRequest) -> Arc<MapTile> {
        Arc::new(MapTile {
            request,
            origin_block_x: 0,
            origin_block_z: 0,
            colors: vec![0; TILE_CELLS],
            heights: vec![0; TILE_CELLS],
            statuses: vec![1; TILE_CELLS],
            successful_chunks: 0,
            missing_chunks: 1,
            unsupported_chunks: 0,
            corrupt_chunks: 0,
        })
    }

    #[test]
    fn weighted_lru_refreshes_access_and_enforces_both_limits() {
        let mut cache = WeightedLru::new(2, 5);
        assert!(cache.insert(1, 1, "one", 2));
        assert!(cache.insert(2, 1, "two", 2));
        assert_eq!(cache.get(1, &1), Some("one"));
        assert!(cache.insert(3, 1, "three", 2));
        assert!(cache.get(2, &1).is_none());
        assert_eq!(cache.get(1, &1), Some("one"));
        assert_eq!(cache.get(3, &1), Some("three"));
        assert!(!cache.insert(4, 1, "oversized", 6));
        assert_eq!(cache.entries.len(), 2);
        assert!(cache.bytes <= 5);
    }

    #[test]
    fn source_change_invalidates_instead_of_serving_stale_tile() {
        let cache = MapSessionCache::with_limits(2, 1024, 2, 2 * 1024 * 1024);
        let request = MapTileRequest {
            tile_x: 0,
            tile_z: 0,
            blocks_per_pixel: 1,
        };
        assert!(cache.put_tile(
            MapCacheScope::OVERWORLD_SURFACE_V1,
            request,
            vec![source(1)],
            tile(request),
        ));
        assert!(cache
            .get_tile(MapCacheScope::OVERWORLD_SURFACE_V1, request, &[source(1)])
            .is_some());
        assert!(cache
            .get_tile(MapCacheScope::OVERWORLD_SURFACE_V1, request, &[source(2)])
            .is_none());
        assert_eq!(cache.stats().tile_entries, 0);
    }

    #[test]
    fn region_invalidation_preserves_other_sources_and_accounting() {
        let cache = MapSessionCache::with_limits(4, 1024, 1, 1024);
        let scope = MapCacheScope::OVERWORLD_SURFACE_V1;
        let mut other_region = source(1);
        other_region.region_x = 1;
        cache.put_chunk(
            scope,
            ChunkCoordinate { x: 0, z: 0 },
            source(1),
            ChunkMapOutcome::Missing,
        );
        cache.put_chunk(
            scope,
            ChunkCoordinate { x: 1, z: 0 },
            source(2),
            ChunkMapOutcome::Missing,
        );
        cache.put_chunk(
            scope,
            ChunkCoordinate { x: 32, z: 0 },
            other_region.clone(),
            ChunkMapOutcome::Missing,
        );

        cache.invalidate_region_source(scope, &source(1));

        assert_eq!(cache.stats().chunk_entries, 2);
        assert_eq!(cache.stats().chunk_bytes, 128);
        assert!(cache
            .get_chunk(scope, ChunkCoordinate { x: 1, z: 0 }, &source(2))
            .is_some());
        assert!(cache
            .get_chunk(scope, ChunkCoordinate { x: 32, z: 0 }, &other_region)
            .is_some());
    }

    #[test]
    fn oversized_tile_is_not_cached() {
        let cache = MapSessionCache::with_limits(2, 1024, 2, 1);
        let request = MapTileRequest {
            tile_x: 0,
            tile_z: 0,
            blocks_per_pixel: 1,
        };
        assert!(!cache.put_tile(
            MapCacheScope::OVERWORLD_SURFACE_V1,
            request,
            vec![source(1)],
            tile(request),
        ));
        assert_eq!(cache.stats().tile_entries, 0);
        assert_eq!(cache.stats().tile_bytes, 0);
    }

    #[test]
    fn clear_releases_both_cache_levels() {
        let cache = MapSessionCache::with_limits(2, 1024, 2, 2 * 1024 * 1024);
        let request = MapTileRequest {
            tile_x: 0,
            tile_z: 0,
            blocks_per_pixel: 1,
        };
        cache.put_chunk(
            MapCacheScope::OVERWORLD_SURFACE_V1,
            ChunkCoordinate { x: 0, z: 0 },
            source(1),
            ChunkMapOutcome::Missing,
        );
        cache.put_tile(
            MapCacheScope::OVERWORLD_SURFACE_V1,
            request,
            vec![source(1)],
            tile(request),
        );
        cache.clear();
        let stats = cache.stats();
        assert_eq!(stats.chunk_entries, 0);
        assert_eq!(stats.chunk_bytes, 0);
        assert_eq!(stats.tile_entries, 0);
        assert_eq!(stats.tile_bytes, 0);
        assert_eq!(stats.progress_entries, 0);
        assert_eq!(stats.progress_bytes, 0);
    }

    #[test]
    fn progress_is_bounded_and_source_checked() {
        let cache = MapSessionCache::default();
        let scope = MapCacheScope::OVERWORLD_SURFACE_V1;
        let first = MapTileRequest {
            tile_x: 0,
            tile_z: 0,
            blocks_per_pixel: 1,
        };
        let second = MapTileRequest { tile_x: 1, ..first };
        let progress = TileProgress::new(256);
        let bytes = progress.storage_budget_bytes();
        {
            let mut state = lock(&cache.state);
            state.progress.max_entries = 1;
            state.progress.max_bytes = bytes;
        }
        assert!(cache.put_progress(scope, first, vec![source(1)], progress.clone()));
        assert!(cache.put_progress(scope, second, vec![source(1)], progress.clone()));
        assert_eq!(cache.stats().progress_entries, 1);
        assert_eq!(cache.stats().progress_bytes, bytes);
        assert!(cache.take_progress(scope, first, &[source(1)]).is_none());
        assert!(cache.take_progress(scope, second, &[source(2)]).is_none());
        assert_eq!(cache.stats().progress_entries, 0);
        assert!(cache.put_progress(scope, first, vec![source(1)], progress));
        cache.clear();
        assert_eq!(cache.stats().progress_entries, 0);
        assert_eq!(cache.stats().progress_bytes, 0);
    }
}
