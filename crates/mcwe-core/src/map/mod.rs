mod cache;

pub use cache::{
    MapCacheScope, MapCacheStats, MapSessionCache, CHUNK_CACHE_MAX_BYTES, CHUNK_CACHE_MAX_ENTRIES,
    PROGRESS_CACHE_MAX_BYTES, PROGRESS_CACHE_MAX_ENTRIES, TILE_CACHE_MAX_BYTES,
    TILE_CACHE_MAX_ENTRIES,
};

use crate::{
    anvil::{ChunkCoordinate, RegionReader},
    cancel::Cancellation,
    chunk::decode_chunk,
    surface::sample_chunk,
    CoreError, WorldSource,
};
use cache::{ChunkMapData, ChunkMapOutcome, RegionSourceState};
use std::{
    collections::HashMap,
    mem::size_of,
    sync::Arc,
    time::{Duration, Instant},
};

pub const TILE_PIXELS: usize = 256;
const TILE_CELLS: usize = TILE_PIXELS * TILE_PIXELS;
const PARTIAL_INTERVAL: Duration = Duration::from_millis(100);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MapTileRequest {
    pub tile_x: i32,
    pub tile_z: i32,
    pub blocks_per_pixel: u8,
}

impl MapTileRequest {
    pub fn bounds(self) -> Result<(i32, i32, i32, i32), CoreError> {
        if !matches!(self.blocks_per_pixel, 1 | 2 | 4 | 8 | 16) {
            return Err(CoreError::InvalidPath);
        }
        let size = i32::try_from(TILE_PIXELS)
            .ok()
            .and_then(|pixels| pixels.checked_mul(i32::from(self.blocks_per_pixel)))
            .ok_or(CoreError::ResourceLimit)?;
        let min_x = self
            .tile_x
            .checked_mul(size)
            .ok_or(CoreError::ResourceLimit)?;
        let min_z = self
            .tile_z
            .checked_mul(size)
            .ok_or(CoreError::ResourceLimit)?;
        let max_x = min_x.checked_add(size).ok_or(CoreError::ResourceLimit)?;
        let max_z = min_z.checked_add(size).ok_or(CoreError::ResourceLimit)?;
        Ok((min_x, min_z, max_x, max_z))
    }
}

#[derive(Debug, Clone)]
pub struct MapTile {
    pub request: MapTileRequest,
    pub origin_block_x: i32,
    pub origin_block_z: i32,
    pub colors: Vec<u32>,
    pub heights: Vec<i32>,
    pub statuses: Vec<u8>,
    pub successful_chunks: u32,
    pub missing_chunks: u32,
    pub unsupported_chunks: u32,
    pub corrupt_chunks: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapTilePatch {
    pub request: MapTileRequest,
    pub revision: u64,
    pub pixel_x: u16,
    pub pixel_z: u16,
    pub width: u16,
    pub height: u16,
    pub colors: Vec<u32>,
    pub heights: Vec<i32>,
    pub statuses: Vec<u8>,
    pub completed_chunks: u32,
    pub total_chunks: u32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MapTileFocus {
    pub block_x: f64,
    pub block_z: f64,
}

#[derive(Clone)]
pub(crate) struct TileProgress {
    red: Vec<u64>,
    green: Vec<u64>,
    blue: Vec<u64>,
    height: Vec<i64>,
    counts: Vec<u16>,
    statuses: Vec<u8>,
    processed: Vec<u8>,
    chunk_counts: [u32; 4],
    completed_chunks: u32,
}

impl TileProgress {
    fn new(total_chunks: usize) -> Self {
        Self {
            red: vec![0; TILE_CELLS],
            green: vec![0; TILE_CELLS],
            blue: vec![0; TILE_CELLS],
            height: vec![0; TILE_CELLS],
            counts: vec![0; TILE_CELLS],
            statuses: vec![4; TILE_CELLS],
            processed: vec![0; total_chunks],
            chunk_counts: [0; 4],
            completed_chunks: 0,
        }
    }

    fn storage_budget_bytes(&self) -> usize {
        (self.red.capacity()
            + self.green.capacity()
            + self.blue.capacity()
            + self.height.capacity())
            * size_of::<u64>()
            + self.counts.capacity() * size_of::<u16>()
            + self.statuses.capacity()
            + self.processed.capacity()
            + size_of::<Self>()
            + 128
    }
}

pub fn load_map_tile(
    source: &WorldSource,
    request: MapTileRequest,
    cancel: &dyn Cancellation,
) -> Result<MapTile, CoreError> {
    generate_map_tile(source, None, request, None, None, cancel, None)
}

pub fn load_map_tile_cached(
    source: &WorldSource,
    cache: &MapSessionCache,
    request: MapTileRequest,
    cancel: &dyn Cancellation,
) -> Result<Arc<MapTile>, CoreError> {
    load_map_tile_cached_with_progress(source, cache, request, None, cancel, None)
}

pub fn load_map_tile_cached_progressive<F>(
    source: &WorldSource,
    cache: &MapSessionCache,
    request: MapTileRequest,
    cancel: &dyn Cancellation,
    mut publish: F,
) -> Result<Arc<MapTile>, CoreError>
where
    F: FnMut(MapTilePatch) -> Result<(), CoreError>,
{
    load_map_tile_cached_with_progress(source, cache, request, None, cancel, Some(&mut publish))
}

pub fn load_map_tile_cached_progressive_focused<F>(
    source: &WorldSource,
    cache: &MapSessionCache,
    request: MapTileRequest,
    focus: MapTileFocus,
    cancel: &dyn Cancellation,
    mut publish: F,
) -> Result<Arc<MapTile>, CoreError>
where
    F: FnMut(MapTilePatch) -> Result<(), CoreError>,
{
    load_map_tile_cached_with_progress(
        source,
        cache,
        request,
        Some(focus),
        cancel,
        Some(&mut publish),
    )
}

fn load_map_tile_cached_with_progress(
    source: &WorldSource,
    cache: &MapSessionCache,
    request: MapTileRequest,
    focus: Option<MapTileFocus>,
    cancel: &dyn Cancellation,
    publish: Option<&mut dyn FnMut(MapTilePatch) -> Result<(), CoreError>>,
) -> Result<Arc<MapTile>, CoreError> {
    cancel.check()?;
    let scope = MapCacheScope::OVERWORLD_SURFACE_V1;
    let sources = region_source_states(source, request)?;
    if let Some(tile) = cache.get_tile(scope, request, &sources) {
        cancel.check()?;
        return Ok(tile);
    }
    let tile = Arc::new(generate_map_tile(
        source,
        Some(cache),
        request,
        Some(&sources),
        focus,
        cancel,
        publish,
    )?);
    let after = region_source_states(source, request)?;
    if after != sources {
        for (expected, actual) in sources.iter().zip(&after) {
            if expected != actual {
                cache.invalidate_region_source(scope, expected);
            }
        }
        return Err(CoreError::InUse);
    }
    cache.put_tile(scope, request, sources, Arc::clone(&tile));
    cancel.check()?;
    Ok(tile)
}

fn generate_map_tile(
    source: &WorldSource,
    cache: Option<&MapSessionCache>,
    request: MapTileRequest,
    source_states: Option<&[RegionSourceState]>,
    focus: Option<MapTileFocus>,
    cancel: &dyn Cancellation,
    mut publish: Option<&mut dyn FnMut(MapTilePatch) -> Result<(), CoreError>>,
) -> Result<MapTile, CoreError> {
    let scope = MapCacheScope::OVERWORLD_SURFACE_V1;
    let (min_x, min_z, max_x, max_z) = request.bounds()?;
    let min_chunk_x = min_x.div_euclid(16);
    let min_chunk_z = min_z.div_euclid(16);
    let max_chunk_x = (max_x - 1).div_euclid(16);
    let max_chunk_z = (max_z - 1).div_euclid(16);
    let total_chunks = u32::try_from(max_chunk_x - min_chunk_x + 1)
        .ok()
        .and_then(|width| {
            u32::try_from(max_chunk_z - min_chunk_z + 1)
                .ok()
                .and_then(|depth| width.checked_mul(depth))
        })
        .ok_or(CoreError::ResourceLimit)?;
    let mut revision = 0_u64;
    let mut dirty: Option<PatchRect> = None;
    let mut last_partial = None;
    let mut regions = region_work_set(source, request, source_states)?;
    let chunks = prioritized_chunks(
        min_chunk_x,
        min_chunk_z,
        max_chunk_x,
        max_chunk_z,
        focus,
        min_x,
        min_z,
        max_x,
        max_z,
    )?;
    let mut progress = cache
        .and_then(|cache| {
            source_states.and_then(|sources| cache.take_progress(scope, request, sources))
        })
        .filter(|saved| {
            saved.processed.len() == total_chunks as usize && saved.completed_chunks <= total_chunks
        })
        .unwrap_or_else(|| TileProgress::new(total_chunks as usize));

    if progress.completed_chunks > 0 {
        if let Some(callback) = publish.as_deref_mut() {
            revision = 1;
            let published = callback(build_patch(
                request,
                revision,
                PatchRect {
                    min_x: 0,
                    min_z: 0,
                    max_x: TILE_PIXELS,
                    max_z: TILE_PIXELS,
                },
                progress.completed_chunks,
                total_chunks,
                &progress.statuses,
                &progress.red,
                &progress.green,
                &progress.blue,
                &progress.height,
                &progress.counts,
            ));
            if let Err(error) = published {
                save_interrupted_progress(source, cache, scope, request, source_states, progress);
                return Err(error);
            }
            last_partial = Some(Instant::now());
        }
    }

    for coordinate in chunks {
        if let Err(error) = cancel.check() {
            save_interrupted_progress(source, cache, scope, request, source_states, progress);
            return Err(error);
        }
        let chunk_index = ((coordinate.z - min_chunk_z) as usize)
            * ((max_chunk_x - min_chunk_x + 1) as usize)
            + (coordinate.x - min_chunk_x) as usize;
        if progress.processed[chunk_index] != 0 {
            continue;
        }
        let region = regions
            .get_mut(&coordinate.region())
            .ok_or(CoreError::InvalidRegion)?;
        let source_state = region.source.clone();
        let cached = cache.and_then(|cache| cache.get_chunk(scope, coordinate, &source_state));
        let outcome = if let Some(outcome) = cached {
            outcome
        } else {
            let outcome = match region.load(source, coordinate, cancel) {
                Ok(outcome) => outcome,
                Err(error) => {
                    save_interrupted_progress(
                        source,
                        cache,
                        scope,
                        request,
                        source_states,
                        progress,
                    );
                    return Err(error);
                }
            };
            // Keep completed chunk work even if the surrounding tile is cancelled. The cache
            // entry remains tied to this region source identity and is rejected after a change.
            if let Some(cache) = cache {
                cache.put_chunk(scope, coordinate, source_state, outcome.clone());
            }
            outcome
        };
        let (status, sampled) = match &outcome {
            ChunkMapOutcome::Ready(data) => (0, Some(data.surface.as_ref())),
            ChunkMapOutcome::Missing => (1, None),
            ChunkMapOutcome::Unsupported => (2, None),
            ChunkMapOutcome::Corrupt => (3, None),
        };
        progress.chunk_counts[status as usize] += 1;
        accept_chunk(
            request,
            min_x,
            min_z,
            coordinate,
            status,
            sampled,
            &mut progress.statuses,
            &mut progress.red,
            &mut progress.green,
            &mut progress.blue,
            &mut progress.height,
            &mut progress.counts,
        );
        progress.processed[chunk_index] = 1;
        progress.completed_chunks += 1;
        let changed = chunk_patch_rect(request, min_x, min_z, coordinate);
        dirty = Some(match dirty {
            Some(current) => current.union(changed),
            None => changed,
        });
        let now = Instant::now();
        let should_publish = revision == 0
            || last_partial
                .is_some_and(|last: Instant| now.duration_since(last) >= PARTIAL_INTERVAL);
        if should_publish {
            if let (Some(callback), Some(rect)) = (publish.as_deref_mut(), dirty.take()) {
                revision = revision.checked_add(1).ok_or(CoreError::ResourceLimit)?;
                let published = callback(build_patch(
                    request,
                    revision,
                    rect,
                    progress.completed_chunks,
                    total_chunks,
                    &progress.statuses,
                    &progress.red,
                    &progress.green,
                    &progress.blue,
                    &progress.height,
                    &progress.counts,
                ));
                if let Err(error) = published {
                    save_interrupted_progress(
                        source,
                        cache,
                        scope,
                        request,
                        source_states,
                        progress,
                    );
                    return Err(error);
                }
                last_partial = Some(now);
            }
        }
    }

    let sources = regions
        .values()
        .map(|region| region.source.clone())
        .collect::<Vec<_>>();
    drop(regions);
    let mut changed = false;
    for expected in sources {
        if region_source_state(source, expected.region_x, expected.region_z)? != expected {
            if let Some(cache) = cache {
                cache.invalidate_region_source(scope, &expected);
            }
            changed = true;
        }
    }
    if changed {
        return Err(CoreError::InUse);
    }

    let mut colors = vec![0_u32; TILE_CELLS];
    let mut heights = vec![0_i32; TILE_CELLS];
    for index in 0..TILE_CELLS {
        let count = u64::from(progress.counts[index]);
        if count > 0 {
            colors[index] = (progress.red[index].checked_div(count).unwrap_or(0) as u32) << 16
                | (progress.green[index].checked_div(count).unwrap_or(0) as u32) << 8
                | progress.blue[index].checked_div(count).unwrap_or(0) as u32;
            heights[index] = progress.height[index]
                .checked_div_euclid(count as i64)
                .unwrap_or(0) as i32;
            progress.statuses[index] = 0;
        }
    }
    shade(&mut colors, &heights, &progress.statuses);
    Ok(MapTile {
        request,
        origin_block_x: min_x,
        origin_block_z: min_z,
        colors,
        heights,
        statuses: progress.statuses,
        successful_chunks: progress.chunk_counts[0],
        missing_chunks: progress.chunk_counts[1],
        unsupported_chunks: progress.chunk_counts[2],
        corrupt_chunks: progress.chunk_counts[3],
    })
}

fn save_interrupted_progress(
    source: &WorldSource,
    cache: Option<&MapSessionCache>,
    scope: MapCacheScope,
    request: MapTileRequest,
    source_states: Option<&[RegionSourceState]>,
    progress: TileProgress,
) {
    let (Some(cache), Some(expected)) = (cache, source_states) else {
        return;
    };
    if progress.completed_chunks == 0 {
        return;
    }
    match region_source_states(source, request) {
        Ok(actual) if actual == expected => {
            cache.put_progress(scope, request, expected.to_vec(), progress);
        }
        Ok(actual) => {
            for (before, after) in expected.iter().zip(&actual) {
                if before != after {
                    cache.invalidate_region_source(scope, before);
                }
            }
        }
        Err(_) => {}
    }
}

enum RegionAccess {
    Missing,
    Unopened,
    Corrupt,
    Ready(RegionReader),
}

struct RegionWork {
    source: RegionSourceState,
    access: RegionAccess,
}

impl RegionWork {
    fn load(
        &mut self,
        source: &WorldSource,
        coordinate: ChunkCoordinate,
        cancel: &dyn Cancellation,
    ) -> Result<ChunkMapOutcome, CoreError> {
        if matches!(self.access, RegionAccess::Missing) {
            return Ok(ChunkMapOutcome::Missing);
        }
        if matches!(self.access, RegionAccess::Corrupt) {
            return Ok(ChunkMapOutcome::Corrupt);
        }
        if matches!(self.access, RegionAccess::Unopened) {
            self.access =
                match RegionReader::open(source, self.source.region_x, self.source.region_z) {
                    Ok(Some(reader)) => RegionAccess::Ready(reader),
                    Ok(None) => return Err(CoreError::InUse),
                    Err(CoreError::InvalidRegion | CoreError::ResourceLimit) => {
                        RegionAccess::Corrupt
                    }
                    Err(error) => return Err(error),
                };
        }
        match &mut self.access {
            RegionAccess::Ready(reader) => read_chunk_map_outcome(reader, coordinate, cancel),
            RegionAccess::Corrupt => Ok(ChunkMapOutcome::Corrupt),
            RegionAccess::Missing => Ok(ChunkMapOutcome::Missing),
            RegionAccess::Unopened => Err(CoreError::InvalidRegion),
        }
    }
}

fn region_work_set(
    source: &WorldSource,
    request: MapTileRequest,
    source_states: Option<&[RegionSourceState]>,
) -> Result<HashMap<(i32, i32), RegionWork>, CoreError> {
    let mut regions = HashMap::new();
    let states = match source_states {
        Some(states) => states.to_vec(),
        None => region_source_states(source, request)?,
    };
    for source_state in states {
        let access = if matches!(source_state.file, crate::SourceFileState::Missing) {
            RegionAccess::Missing
        } else {
            RegionAccess::Unopened
        };
        regions.insert(
            (source_state.region_x, source_state.region_z),
            RegionWork {
                source: source_state,
                access,
            },
        );
    }
    Ok(regions)
}

#[allow(clippy::too_many_arguments)]
fn prioritized_chunks(
    min_chunk_x: i32,
    min_chunk_z: i32,
    max_chunk_x: i32,
    max_chunk_z: i32,
    focus: Option<MapTileFocus>,
    min_x: i32,
    min_z: i32,
    max_x: i32,
    max_z: i32,
) -> Result<Vec<ChunkCoordinate>, CoreError> {
    let width =
        usize::try_from(max_chunk_x - min_chunk_x + 1).map_err(|_| CoreError::ResourceLimit)?;
    let depth =
        usize::try_from(max_chunk_z - min_chunk_z + 1).map_err(|_| CoreError::ResourceLimit)?;
    let mut chunks = Vec::with_capacity(width.checked_mul(depth).ok_or(CoreError::ResourceLimit)?);
    for chunk_z in min_chunk_z..=max_chunk_z {
        for chunk_x in min_chunk_x..=max_chunk_x {
            chunks.push(ChunkCoordinate {
                x: chunk_x,
                z: chunk_z,
            });
        }
    }
    let Some(focus) = focus else {
        return Ok(chunks);
    };
    let default_x = f64::from(min_x) + f64::from(max_x - min_x) / 2.0;
    let default_z = f64::from(min_z) + f64::from(max_z - min_z) / 2.0;
    let requested_x = focus.block_x;
    let requested_z = focus.block_z;
    let focus_x = if requested_x.is_finite() {
        requested_x.clamp(f64::from(min_x), f64::from(max_x - 1))
    } else {
        default_x
    };
    let focus_z = if requested_z.is_finite() {
        requested_z.clamp(f64::from(min_z), f64::from(max_z - 1))
    } else {
        default_z
    };
    chunks.sort_by(|left, right| {
        chunk_distance_squared(*left, focus_x, focus_z)
            .total_cmp(&chunk_distance_squared(*right, focus_x, focus_z))
            .then_with(|| left.z.cmp(&right.z))
            .then_with(|| left.x.cmp(&right.x))
    });
    Ok(chunks)
}

fn chunk_distance_squared(coordinate: ChunkCoordinate, focus_x: f64, focus_z: f64) -> f64 {
    let center_x = f64::from(coordinate.x) * 16.0 + 8.0;
    let center_z = f64::from(coordinate.z) * 16.0 + 8.0;
    let dx = center_x - focus_x;
    let dz = center_z - focus_z;
    dx * dx + dz * dz
}

fn read_chunk_map_outcome(
    reader: &mut RegionReader,
    coordinate: ChunkCoordinate,
    cancel: &dyn Cancellation,
) -> Result<ChunkMapOutcome, CoreError> {
    let payload = match reader.read_chunk_payload(coordinate, cancel) {
        Ok(Some(payload)) => payload,
        Ok(None) | Err(CoreError::NotFound) => return Ok(ChunkMapOutcome::Missing),
        Err(CoreError::UnsupportedChunk | CoreError::UnsupportedCompression) => {
            return Ok(ChunkMapOutcome::Unsupported)
        }
        Err(
            CoreError::InvalidNbt
            | CoreError::InvalidRegion
            | CoreError::InvalidChunk
            | CoreError::ResourceLimit,
        ) => return Ok(ChunkMapOutcome::Corrupt),
        Err(error) => return Err(error),
    };
    let decoded = match decode_chunk(&payload.bytes, coordinate, cancel) {
        Ok(decoded) => decoded,
        Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
        Err(CoreError::UnsupportedChunk | CoreError::UnsupportedCompression) => {
            return Ok(ChunkMapOutcome::Unsupported)
        }
        Err(
            CoreError::InvalidNbt
            | CoreError::InvalidRegion
            | CoreError::InvalidChunk
            | CoreError::ResourceLimit,
        ) => return Ok(ChunkMapOutcome::Corrupt),
        Err(error) => return Err(error),
    };
    let surface = sample_chunk(&decoded, cancel)?;
    Ok(ChunkMapOutcome::Ready(Arc::new(ChunkMapData {
        decoded: Arc::new(decoded),
        surface: Arc::from(surface),
    })))
}

fn region_source_states(
    source: &WorldSource,
    request: MapTileRequest,
) -> Result<Vec<RegionSourceState>, CoreError> {
    let (min_x, min_z, max_x, max_z) = request.bounds()?;
    let min_region_x = min_x.div_euclid(512);
    let min_region_z = min_z.div_euclid(512);
    let max_region_x = (max_x - 1).div_euclid(512);
    let max_region_z = (max_z - 1).div_euclid(512);
    let mut result = Vec::new();
    for region_z in min_region_z..=max_region_z {
        for region_x in min_region_x..=max_region_x {
            result.push(region_source_state(source, region_x, region_z)?);
        }
    }
    Ok(result)
}

fn region_source_state(
    source: &WorldSource,
    region_x: i32,
    region_z: i32,
) -> Result<RegionSourceState, CoreError> {
    Ok(RegionSourceState {
        region_x,
        region_z,
        file: source.source_file_state(format!("region/r.{region_x}.{region_z}.mca"))?,
    })
}

#[derive(Debug, Clone, Copy)]
struct PatchRect {
    min_x: usize,
    min_z: usize,
    max_x: usize,
    max_z: usize,
}

impl PatchRect {
    fn union(self, other: Self) -> Self {
        Self {
            min_x: self.min_x.min(other.min_x),
            min_z: self.min_z.min(other.min_z),
            max_x: self.max_x.max(other.max_x),
            max_z: self.max_z.max(other.max_z),
        }
    }
}

fn chunk_patch_rect(
    request: MapTileRequest,
    min_x: i32,
    min_z: i32,
    coordinate: ChunkCoordinate,
) -> PatchRect {
    let bpp = i32::from(request.blocks_per_pixel);
    let pixel_x = (coordinate.x * 16 - min_x).div_euclid(bpp) as usize;
    let pixel_z = (coordinate.z * 16 - min_z).div_euclid(bpp) as usize;
    let size = 16_usize / usize::from(request.blocks_per_pixel);
    PatchRect {
        min_x: pixel_x,
        min_z: pixel_z,
        max_x: (pixel_x + size + 1).min(TILE_PIXELS),
        max_z: (pixel_z + size + 1).min(TILE_PIXELS),
    }
}

#[allow(clippy::too_many_arguments)]
fn build_patch(
    request: MapTileRequest,
    revision: u64,
    rect: PatchRect,
    completed_chunks: u32,
    total_chunks: u32,
    statuses: &[u8],
    red: &[u64],
    green: &[u64],
    blue: &[u64],
    height: &[i64],
    counts: &[u16],
) -> MapTilePatch {
    let width = rect.max_x - rect.min_x;
    let patch_height = rect.max_z - rect.min_z;
    let cells = width * patch_height;
    let mut colors = Vec::with_capacity(cells);
    let mut heights = Vec::with_capacity(cells);
    let mut patch_statuses = Vec::with_capacity(cells);
    for z in rect.min_z..rect.max_z {
        for x in rect.min_x..rect.max_x {
            let index = z * TILE_PIXELS + x;
            let (mut color, cell_height, status) =
                averaged_cell(index, statuses, red, green, blue, height, counts);
            if status == 0 {
                let mut neighbor_sum = 0_i64;
                let mut neighbor_count = 0_i64;
                if x > 0 {
                    let (_, value, neighbor_status) =
                        averaged_cell(index - 1, statuses, red, green, blue, height, counts);
                    if neighbor_status == 0 {
                        neighbor_sum += i64::from(value);
                        neighbor_count += 1;
                    }
                }
                if z > 0 {
                    let (_, value, neighbor_status) = averaged_cell(
                        index - TILE_PIXELS,
                        statuses,
                        red,
                        green,
                        blue,
                        height,
                        counts,
                    );
                    if neighbor_status == 0 {
                        neighbor_sum += i64::from(value);
                        neighbor_count += 1;
                    }
                }
                let difference = if neighbor_count == 0 {
                    0
                } else {
                    i64::from(cell_height) - neighbor_sum.div_euclid(neighbor_count)
                };
                let factor = (1.0 + difference as f64 * 0.035).clamp(0.78, 1.22);
                let channel = |shift: u32| {
                    (((color >> shift) & 0xff) as f64 * factor)
                        .round()
                        .clamp(0.0, 255.0) as u32
                };
                color = channel(16) << 16 | channel(8) << 8 | channel(0);
            }
            colors.push(color);
            heights.push(cell_height);
            patch_statuses.push(status);
        }
    }
    MapTilePatch {
        request,
        revision,
        pixel_x: rect.min_x as u16,
        pixel_z: rect.min_z as u16,
        width: width as u16,
        height: patch_height as u16,
        colors,
        heights,
        statuses: patch_statuses,
        completed_chunks,
        total_chunks,
    }
}

#[allow(clippy::too_many_arguments)]
fn averaged_cell(
    index: usize,
    statuses: &[u8],
    red: &[u64],
    green: &[u64],
    blue: &[u64],
    height: &[i64],
    counts: &[u16],
) -> (u32, i32, u8) {
    let count = u64::from(counts[index]);
    if count == 0 {
        return (0, 0, statuses[index]);
    }
    (
        ((red[index] / count) as u32) << 16
            | ((green[index] / count) as u32) << 8
            | (blue[index] / count) as u32,
        height[index].div_euclid(count as i64) as i32,
        0,
    )
}

#[allow(clippy::too_many_arguments)]
fn accept_chunk(
    request: MapTileRequest,
    min_x: i32,
    min_z: i32,
    coordinate: ChunkCoordinate,
    status: u8,
    sampled: Option<&[crate::surface::SurfaceCell]>,
    statuses: &mut [u8],
    red: &mut [u64],
    green: &mut [u64],
    blue: &mut [u64],
    heights: &mut [i64],
    counts: &mut [u16],
) {
    for local_z in 0..16 {
        for local_x in 0..16 {
            let world_x = coordinate.x * 16 + local_x as i32;
            let world_z = coordinate.z * 16 + local_z as i32;
            let pixel_x = (world_x - min_x) / i32::from(request.blocks_per_pixel);
            let pixel_z = (world_z - min_z) / i32::from(request.blocks_per_pixel);
            let index = pixel_z as usize * TILE_PIXELS + pixel_x as usize;
            statuses[index] = status;
            if let Some(sampled) = sampled {
                let cell = sampled[local_z * 16 + local_x];
                red[index] += u64::from((cell.color >> 16) & 0xff);
                green[index] += u64::from((cell.color >> 8) & 0xff);
                blue[index] += u64::from(cell.color & 0xff);
                heights[index] += i64::from(cell.height);
                counts[index] += 1;
            }
        }
    }
}

fn shade(colors: &mut [u32], heights: &[i32], statuses: &[u8]) {
    let base = colors.to_vec();
    for z in 0..TILE_PIXELS {
        for x in 0..TILE_PIXELS {
            let index = z * TILE_PIXELS + x;
            if statuses[index] != 0 {
                continue;
            }
            let mut sum = 0_i64;
            let mut count = 0_i64;
            if x > 0 && statuses[index - 1] == 0 {
                sum += i64::from(heights[index - 1]);
                count += 1;
            }
            if z > 0 && statuses[index - TILE_PIXELS] == 0 {
                sum += i64::from(heights[index - TILE_PIXELS]);
                count += 1;
            }
            let difference = if count == 0 {
                0
            } else {
                i64::from(heights[index]) - sum.div_euclid(count)
            };
            let factor = (1.0 + difference as f64 * 0.035).clamp(0.78, 1.22);
            let channel = |shift: u32| {
                (((base[index] >> shift) & 0xff_u32) as f64 * factor)
                    .round()
                    .clamp(0.0, 255.0) as u32
            };
            colors[index] = channel(16) << 16 | channel(8) << 8 | channel(0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tile_bounds_match_java_for_negative_coordinates_and_all_levels() {
        for blocks_per_pixel in [1, 2, 4, 8, 16] {
            let request = MapTileRequest {
                tile_x: -1,
                tile_z: 2,
                blocks_per_pixel,
            };
            let size = 256 * i32::from(blocks_per_pixel);
            assert_eq!(request.bounds().unwrap(), (-size, size * 2, 0, size * 3));
        }
    }

    #[test]
    fn tile_bounds_reject_levels_and_overflow() {
        assert!(MapTileRequest {
            tile_x: 0,
            tile_z: 0,
            blocks_per_pixel: 3
        }
        .bounds()
        .is_err());
        assert!(MapTileRequest {
            tile_x: i32::MAX,
            tile_z: 0,
            blocks_per_pixel: 16
        }
        .bounds()
        .is_err());
    }
}
