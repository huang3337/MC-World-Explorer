use crate::{
    error::{AppError, ErrorCode},
    protocol::{encode_map_batch_v1, MapBatchState},
    tasks::{
        MapFailureKind, MapJobKey, MapPollError, MapPollState, MapPriority, MapResultIdentity,
        MapResultItem, MapResultPayload, MapResultQueueError, MapSchedulerError, TaskState,
        MAP_PENDING_CAPACITY,
    },
    world_session::{WorldSession, WorldSessionState},
};
use mcwe_core::{map::MapTileRequest, CoreError};
use serde::Serialize;
use std::{collections::HashSet, sync::Arc, time::Duration};

const MAX_VIEWPORT_CSS_PIXELS: u32 = 16_384;
const MAX_VISIBLE_TILES: usize = 64;
const MAX_PREFETCH_TILES: usize = 64;
const ANCHOR_PREFETCH_RADIUS: i32 = 6;
const LONG_POLL_TIMEOUT: Duration = Duration::from_millis(250);

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MapViewportSummary {
    session_id: u64,
    viewport_task_id: u64,
    blocks_per_pixel: u8,
    target_count: usize,
    targets: Vec<MapTargetSummary>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
struct MapTargetSummary {
    tile_x: i32,
    tile_z: i32,
    blocks_per_pixel: u8,
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub(super) fn start_map_viewport<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    session_id: u64,
    center_x: f64,
    center_z: f64,
    viewport_width: u32,
    viewport_height: u32,
    blocks_per_pixel: u8,
    display_blocks_per_pixel: f64,
    sessions: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<MapViewportSummary, AppError> {
    require_main(&window)?;
    super::require_json_arguments(
        &request,
        &[
            "sessionId",
            "centerX",
            "centerZ",
            "viewportWidth",
            "viewportHeight",
            "blocksPerPixel",
            "displayBlocksPerPixel",
        ],
    )?;
    let session = sessions.get(session_id)?;
    let targets = visible_targets(
        center_x,
        center_z,
        viewport_width,
        viewport_height,
        blocks_per_pixel,
        display_blocks_per_pixel,
    )?;
    let load_focus = (
        f64::from(session.info.map_load_anchor.position.x),
        f64::from(session.info.map_load_anchor.position.z),
    );
    let retained = targets
        .iter()
        .map(|target| MapJobKey::new(session_id, target.tile_x, target.tile_z, blocks_per_pixel))
        .collect::<Result<HashSet<_>, _>>()
        .map_err(map_scheduler_error)?;
    let prefetch = anchor_prefetch_targets(&targets, load_focus.0, load_focus.1, blocks_per_pixel)?
        .into_iter()
        .map(|target| MapJobKey::new(session_id, target.tile_x, target.tile_z, blocks_per_pixel))
        .collect::<Result<Vec<_>, _>>()
        .map_err(map_scheduler_error)?;
    let background = prefetch
        .iter()
        .take(MAP_PENDING_CAPACITY.saturating_sub(targets.len()))
        .copied()
        .collect::<HashSet<_>>();
    // 世界提交与视口登记必须互斥，避免切换已取消旧任务后旧轮询状态又被登记。
    let viewport_id = sessions.with_current(session_id, || {
        let viewport_id = tasks
            .begin_map_viewport_with_background(session_id, &retained, &background)
            .map_err(map_scheduler_error)?;
        tasks.track_map_viewport(session_id, viewport_id, targets.len(), prefetch);

        for target in &targets {
            let key = MapJobKey::new(session_id, target.tile_x, target.tile_z, blocks_per_pixel)
                .map_err(map_scheduler_error)?;
            if let Err(error) = submit_tile(
                &tasks,
                viewport_id,
                key,
                MapPriority::visible(target.distance_squared, target.tile_x, target.tile_z),
                Arc::clone(&session),
                Some(load_focus),
            ) {
                tasks.abort_map_viewport(session_id, viewport_id);
                return Err(map_scheduler_error(error));
            }
        }
        Ok(viewport_id)
    })??;

    if !sessions.is_current(session_id) {
        tasks.abort_map_viewport(session_id, viewport_id);
        return Err(AppError::new(ErrorCode::SessionOutdated));
    }

    Ok(MapViewportSummary {
        session_id,
        viewport_task_id: viewport_id,
        blocks_per_pixel,
        target_count: targets.len(),
        targets: targets
            .iter()
            .map(|target| MapTargetSummary {
                tile_x: target.tile_x,
                tile_z: target.tile_z,
                blocks_per_pixel,
            })
            .collect(),
    })
}

#[tauri::command]
pub(super) async fn next_map_viewport_batch<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    session_id: u64,
    viewport_task_id: u64,
    sessions: tauri::State<'_, WorldSessionState>,
    tasks: tauri::State<'_, TaskState>,
) -> Result<tauri::ipc::Response, AppError> {
    require_main(&window)?;
    super::require_json_arguments(&request, &["sessionId", "viewportTaskId"])?;
    let session = sessions.get(session_id)?;
    let poll = tasks
        .begin_map_poll(session_id, viewport_task_id)
        .map_err(map_poll_error)?;
    let batch = tauri::async_runtime::spawn_blocking(move || poll.wait(LONG_POLL_TIMEOUT))
        .await
        .map_err(|_| AppError::new(ErrorCode::WorkerFailed))?
        .map_err(map_poll_error)?;
    if !sessions.is_current(session_id) {
        return Err(AppError::new(ErrorCode::SessionOutdated));
    }
    if batch.state == MapPollState::Ended {
        for (index, key) in batch.prefetch.iter().copied().enumerate() {
            let priority = MapPriority::prefetch(index as u64, key.tile_x(), key.tile_z());
            if submit_tile(
                &tasks,
                viewport_task_id,
                key,
                priority,
                Arc::clone(&session),
                None,
            )
            .is_err()
            {
                break;
            }
        }
    }
    let state = match batch.state {
        MapPollState::Items => MapBatchState::Items,
        MapPollState::Timeout => MapBatchState::Timeout,
        MapPollState::Ended => MapBatchState::Ended,
        MapPollState::ShuttingDown => MapBatchState::ShuttingDown,
    };
    let bytes = encode_map_batch_v1(
        session_id,
        viewport_task_id,
        batch.sequence,
        &batch.items,
        batch.has_more,
        state,
    )?;
    Ok(tauri::ipc::Response::new(bytes))
}

#[tauri::command]
pub(super) fn cancel_map_viewport<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request: tauri::ipc::Request<'_>,
    session_id: u64,
    viewport_task_id: u64,
    tasks: tauri::State<'_, TaskState>,
) -> Result<(), AppError> {
    require_main(&window)?;
    super::require_json_arguments(&request, &["sessionId", "viewportTaskId"])?;
    if session_id == 0
        || session_id > crate::protocol::MAX_SAFE_INTEGER_ID
        || viewport_task_id == 0
        || viewport_task_id > crate::protocol::MAX_SAFE_INTEGER_ID
    {
        return Err(AppError::new(ErrorCode::InvalidArguments));
    }
    tasks.abort_map_viewport(session_id, viewport_task_id);
    Ok(())
}

#[derive(Debug, Clone, Copy)]
struct VisibleTarget {
    tile_x: i32,
    tile_z: i32,
    distance_squared: u64,
}

fn visible_targets(
    center_x: f64,
    center_z: f64,
    width: u32,
    height: u32,
    blocks_per_pixel: u8,
    display_blocks_per_pixel: f64,
) -> Result<Vec<VisibleTarget>, AppError> {
    if !center_x.is_finite()
        || !center_z.is_finite()
        || width == 0
        || height == 0
        || width > MAX_VIEWPORT_CSS_PIXELS
        || height > MAX_VIEWPORT_CSS_PIXELS
        || !matches!(blocks_per_pixel, 1 | 2 | 4 | 8 | 16)
        || !display_blocks_per_pixel.is_finite()
        || !(0.25..=16.0).contains(&display_blocks_per_pixel)
    {
        return Err(AppError::new(ErrorCode::InvalidArguments));
    }
    let tile_size = f64::from(256_u32 * u32::from(blocks_per_pixel));
    let half_width = f64::from(width) * display_blocks_per_pixel / 2.0;
    let half_height = f64::from(height) * display_blocks_per_pixel / 2.0;
    let bounds = [
        ((center_x - half_width) / tile_size).floor(),
        ((center_x + half_width) / tile_size).floor(),
        ((center_z - half_height) / tile_size).floor(),
        ((center_z + half_height) / tile_size).floor(),
    ];
    if bounds
        .iter()
        .any(|value| *value < f64::from(i32::MIN) || *value > f64::from(i32::MAX))
    {
        return Err(AppError::new(ErrorCode::ResourceLimit));
    }
    let (min_x, max_x, min_z, max_z) = (
        bounds[0] as i32,
        bounds[1] as i32,
        bounds[2] as i32,
        bounds[3] as i32,
    );
    let count = (i64::from(max_x) - i64::from(min_x) + 1)
        .checked_mul(i64::from(max_z) - i64::from(min_z) + 1)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| AppError::new(ErrorCode::ResourceLimit))?;
    if count == 0 || count > MAX_VISIBLE_TILES {
        return Err(AppError::new(ErrorCode::ResourceLimit));
    }
    let mut targets = Vec::with_capacity(count);
    for tile_z in min_z..=max_z {
        for tile_x in min_x..=max_x {
            MapTileRequest {
                tile_x,
                tile_z,
                blocks_per_pixel,
            }
            .bounds()
            .map_err(AppError::from)?;
            let dx = (f64::from(tile_x) + 0.5) - center_x / tile_size;
            let dz = (f64::from(tile_z) + 0.5) - center_z / tile_size;
            targets.push(VisibleTarget {
                tile_x,
                tile_z,
                distance_squared: ((dx * dx + dz * dz) * 1_000_000.0)
                    .min(u64::MAX as f64)
                    .round() as u64,
            });
        }
    }
    targets.sort_by_key(|target| (target.distance_squared, target.tile_z, target.tile_x));
    Ok(targets)
}

fn anchor_prefetch_targets(
    visible: &[VisibleTarget],
    anchor_x: f64,
    anchor_z: f64,
    blocks_per_pixel: u8,
) -> Result<Vec<VisibleTarget>, AppError> {
    if visible.is_empty() || !anchor_x.is_finite() || !anchor_z.is_finite() {
        return Err(AppError::new(ErrorCode::InvalidArguments));
    }
    let tile_size = f64::from(256_u32 * u32::from(blocks_per_pixel));
    let anchor_tile_x = (anchor_x / tile_size).floor();
    let anchor_tile_z = (anchor_z / tile_size).floor();
    if anchor_tile_x < f64::from(i32::MIN)
        || anchor_tile_x > f64::from(i32::MAX)
        || anchor_tile_z < f64::from(i32::MIN)
        || anchor_tile_z > f64::from(i32::MAX)
    {
        return Err(AppError::new(ErrorCode::ResourceLimit));
    }
    let anchor_tile_x = anchor_tile_x as i32;
    let anchor_tile_z = anchor_tile_z as i32;
    let visible_keys = visible
        .iter()
        .map(|target| (target.tile_x, target.tile_z))
        .collect::<HashSet<_>>();
    let mut targets = Vec::new();
    for offset_z in -ANCHOR_PREFETCH_RADIUS..=ANCHOR_PREFETCH_RADIUS {
        for offset_x in -ANCHOR_PREFETCH_RADIUS..=ANCHOR_PREFETCH_RADIUS {
            let (Some(tile_x), Some(tile_z)) = (
                anchor_tile_x.checked_add(offset_x),
                anchor_tile_z.checked_add(offset_z),
            ) else {
                continue;
            };
            if visible_keys.contains(&(tile_x, tile_z)) {
                continue;
            }
            MapTileRequest {
                tile_x,
                tile_z,
                blocks_per_pixel,
            }
            .bounds()
            .map_err(AppError::from)?;
            let dx = (f64::from(tile_x) + 0.5) - anchor_x / tile_size;
            let dz = (f64::from(tile_z) + 0.5) - anchor_z / tile_size;
            targets.push(VisibleTarget {
                tile_x,
                tile_z,
                distance_squared: ((dx * dx + dz * dz) * 1_000_000.0)
                    .min(u64::MAX as f64)
                    .round() as u64,
            });
        }
    }
    targets.sort_by_key(|target| (target.distance_squared, target.tile_z, target.tile_x));
    targets.truncate(MAX_PREFETCH_TILES);
    Ok(targets)
}

fn submit_tile(
    tasks: &TaskState,
    viewport_id: u64,
    key: MapJobKey,
    priority: MapPriority,
    source: Arc<WorldSession>,
    focus: Option<(f64, f64)>,
) -> Result<(), MapSchedulerError> {
    let request = MapTileRequest {
        tile_x: key.tile_x(),
        tile_z: key.tile_z(),
        blocks_per_pixel: key.blocks_per_pixel(),
    };
    let results = tasks.map_results();
    tasks
        .submit_map(viewport_id, key, priority, move |worker| {
            let mut publish = |patch| {
                if !worker.should_publish_results() {
                    return Ok(());
                }
                let identity =
                    MapResultIdentity::new(worker.session_id(), worker.viewport_id(), key)
                        .map_err(map_result_error)?;
                results
                    .push_partial(MapResultItem {
                        identity,
                        payload: MapResultPayload::Partial(patch),
                    })
                    .map_err(map_result_error)?;
                Ok(())
            };
            let generated = match focus {
                Some((block_x, block_z)) => {
                    mcwe_core::map::load_map_tile_cached_progressive_focused(
                        &source.source,
                        &source.map_cache,
                        request,
                        mcwe_core::map::MapTileFocus { block_x, block_z },
                        &worker,
                        &mut publish,
                    )
                }
                None => mcwe_core::map::load_map_tile_cached_progressive(
                    &source.source,
                    &source.map_cache,
                    request,
                    &worker,
                    &mut publish,
                ),
            };
            if worker.is_cancelled() {
                return;
            }
            worker.mark_completed();
            if !worker.should_publish_results() {
                return;
            }
            let identity =
                match MapResultIdentity::new(worker.session_id(), worker.viewport_id(), key) {
                    Ok(identity) => identity,
                    Err(_) => return,
                };
            let payload = match generated {
                Ok(tile) => MapResultPayload::Ready(tile),
                Err(CoreError::Cancelled) => return,
                Err(_) => MapResultPayload::Failed(MapFailureKind::ReadFailed),
            };
            let _ = results.push_terminal(MapResultItem { identity, payload }, &worker);
        })
        .map(|_| ())
}

fn require_main<R: tauri::Runtime>(window: &tauri::WebviewWindow<R>) -> Result<(), AppError> {
    if window.label() == "main" {
        Ok(())
    } else {
        Err(AppError::new(ErrorCode::PermissionDenied))
    }
}

fn map_scheduler_error(error: MapSchedulerError) -> AppError {
    AppError::new(match error {
        MapSchedulerError::InvalidKey => ErrorCode::InvalidArguments,
        MapSchedulerError::ViewportOutdated => ErrorCode::TaskOutdated,
        MapSchedulerError::IdExhausted => ErrorCode::TaskIdExhausted,
        MapSchedulerError::QueueFull => ErrorCode::ResourceLimit,
        MapSchedulerError::WorkerUnavailable => ErrorCode::WorkerFailed,
        MapSchedulerError::ShuttingDown => ErrorCode::ShuttingDown,
    })
}

fn map_poll_error(error: MapPollError) -> AppError {
    AppError::new(match error {
        MapPollError::Invalid => ErrorCode::InvalidArguments,
        MapPollError::Outdated => ErrorCode::TaskOutdated,
        MapPollError::ShuttingDown => ErrorCode::ShuttingDown,
    })
}

fn map_result_error(error: MapResultQueueError) -> CoreError {
    match error {
        MapResultQueueError::Cancelled
        | MapResultQueueError::OutdatedRevision
        | MapResultQueueError::ShuttingDown => CoreError::Cancelled,
        MapResultQueueError::InvalidResult => CoreError::InvalidChunk,
        MapResultQueueError::ItemTooLarge => CoreError::ResourceLimit,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visible_targets_are_bounded_center_first_and_support_negative_coordinates() {
        let targets = visible_targets(-1.0, -1.0, 800, 520, 2, 2.0).unwrap();
        assert!(!targets.is_empty());
        assert!(targets.len() <= MAX_VISIBLE_TILES);
        assert_eq!((targets[0].tile_x, targets[0].tile_z), (-1, -1));
        assert!(targets
            .windows(2)
            .all(|pair| pair[0].distance_squared <= pair[1].distance_squared));
    }

    #[test]
    fn visible_targets_reject_invalid_zoom_dimensions_and_coordinate_overflow() {
        assert!(visible_targets(0.0, 0.0, 0, 1, 1, 1.0).is_err());
        assert!(visible_targets(0.0, 0.0, 1, 1, 3, 1.0).is_err());
        assert!(visible_targets(0.0, 0.0, 1, 1, 1, f64::NAN).is_err());
        assert!(visible_targets(0.0, 0.0, 1, 1, 1, 0.1).is_err());
        assert!(visible_targets(f64::from(i32::MAX), 0.0, 16_384, 1, 16, 16.0).is_err());
    }

    #[test]
    fn visible_targets_use_display_footprint_and_data_tile_size() {
        let zoomed = visible_targets(128.0, 128.0, 800, 520, 1, 0.25).unwrap();
        assert_eq!(zoomed.len(), 1);
        assert_eq!((zoomed[0].tile_x, zoomed[0].tile_z), (0, 0));

        let boundary = visible_targets(-1.0, -1.0, 800, 520, 1, 0.5).unwrap();
        let coords = boundary
            .iter()
            .map(|target| (target.tile_x, target.tile_z))
            .collect::<HashSet<_>>();
        assert_eq!(coords, HashSet::from([(-1, -1), (-1, 0), (0, -1), (0, 0)]));
    }

    #[test]
    fn prefetch_is_bounded_around_the_fixed_load_anchor() {
        let visible = visible_targets(0.0, 0.0, 800, 520, 2, 2.0).unwrap();
        let prefetch = anchor_prefetch_targets(&visible, -4032.0, 2112.0, 2).unwrap();
        let visible_keys = visible
            .iter()
            .map(|target| (target.tile_x, target.tile_z))
            .collect::<HashSet<_>>();
        assert!(!prefetch.is_empty());
        assert!(prefetch.len() <= MAX_PREFETCH_TILES);
        assert!(prefetch
            .iter()
            .all(|target| !visible_keys.contains(&(target.tile_x, target.tile_z))));
        assert!(prefetch
            .windows(2)
            .all(|pair| pair[0].distance_squared <= pair[1].distance_squared));

        assert_eq!(prefetch.len(), MAX_PREFETCH_TILES);
        assert_eq!((prefetch[0].tile_x, prefetch[0].tile_z), (-8, 4));
    }

    #[test]
    fn anchored_prefetch_does_not_move_with_a_distant_viewport() {
        let first_visible = visible_targets(10_000.0, 10_000.0, 800, 520, 2, 2.0).unwrap();
        let second_visible = visible_targets(20_000.0, -20_000.0, 800, 520, 2, 2.0).unwrap();
        let first = anchor_prefetch_targets(&first_visible, -64.0, -64.0, 2).unwrap();
        let second = anchor_prefetch_targets(&second_visible, -64.0, -64.0, 2).unwrap();
        assert_eq!(
            first
                .iter()
                .map(|target| (target.tile_x, target.tile_z))
                .collect::<Vec<_>>(),
            second
                .iter()
                .map(|target| (target.tile_x, target.tile_z))
                .collect::<Vec<_>>()
        );
    }
}
