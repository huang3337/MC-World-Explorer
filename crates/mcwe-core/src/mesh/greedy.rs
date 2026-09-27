use super::{
    classify, ChunkFailure, ChunkRect, MeshBatch, MeshBounds, MeshData, MeshWarning,
    MeshWarningKind, Neighborhood, RenderClass, RenderLayer,
};
use crate::{
    anvil::ChunkCoordinate,
    cancel::Cancellation,
    chunk::BlockState,
    limits::{MAX_MESH_BATCHES, MAX_MESH_FACES, MAX_MESH_INDICES, MAX_MESH_VERTICES},
    CoreError,
};
use std::collections::{BTreeMap, BTreeSet};

type Key = (RenderLayer, u32, u32);
#[derive(Clone, Copy)]
enum Direction {
    PosX,
    NegX,
    PosY,
    NegY,
    PosZ,
    NegZ,
}
#[derive(Clone, Copy)]
struct Rect {
    direction: Direction,
    fixed: i32,
    u: i32,
    v: i32,
    width: i32,
    height: i32,
}
#[derive(Clone, Copy)]
struct MergeSpec {
    width: i32,
    height: i32,
    direction: Direction,
    fixed: i32,
    u_offset: i32,
    v_offset: i32,
}

pub fn build_mesh(
    n: &Neighborhood,
    rect: ChunkRect,
    cancel: &dyn Cancellation,
) -> Result<MeshData, CoreError> {
    cancel.check()?;
    rect.validate()?;
    let origin_x = rect.min_x.checked_mul(16).ok_or(CoreError::ResourceLimit)?;
    let origin_z = rect.min_z.checked_mul(16).ok_or(CoreError::ResourceLimit)?;
    let size_x = i32::from(rect.width) * 16;
    let size_z = i32::from(rect.depth) * 16;
    let end_x = rect.min_x + i32::from(rect.width);
    let end_z = rect.min_z + i32::from(rect.depth);
    let mut min_y = i32::MAX;
    let mut max_y = i32::MIN;
    let mut warnings = Vec::new();
    let mut available_targets = 0_u8;
    for cz in rect.min_z..end_z {
        for cx in rect.min_x..end_x {
            let coordinate = ChunkCoordinate { x: cx, z: cz };
            if let Some(chunk) = n.chunks.get(&coordinate) {
                available_targets += 1;
                if let Some((&lo, _)) = chunk.sections.first_key_value() {
                    min_y = min_y.min(i32::from(lo) * 16);
                }
                if let Some((&hi, _)) = chunk.sections.last_key_value() {
                    max_y = max_y.max(i32::from(hi) * 16 + 15);
                }
            } else {
                warnings.push(MeshWarning {
                    kind: MeshWarningKind::TargetUnavailable,
                    coordinate,
                    failure: Some(
                        n.failures
                            .get(&coordinate)
                            .copied()
                            .unwrap_or(ChunkFailure::Missing),
                    ),
                    block_name: None,
                    occurrences: 1,
                });
            }
        }
    }
    if available_targets == 0 {
        return Err(CoreError::UnsupportedChunk);
    }
    for coordinate in halo_coordinates(rect) {
        if !n.chunks.contains_key(&coordinate) {
            warnings.push(MeshWarning {
                kind: MeshWarningKind::NeighborUnavailable,
                coordinate,
                failure: Some(
                    n.failures
                        .get(&coordinate)
                        .copied()
                        .unwrap_or(ChunkFailure::Missing),
                ),
                block_name: None,
                occurrences: 1,
            });
        }
    }
    if min_y > max_y {
        return Ok(MeshData {
            origin_world_x: origin_x,
            origin_world_z: origin_z,
            warnings,
            ..MeshData::default()
        });
    }
    let size_y = max_y - min_y + 1;
    let mut groups: BTreeMap<Key, Vec<Rect>> = BTreeMap::new();
    let mut block_counts: BTreeMap<Key, u32> = BTreeMap::new();
    let mut fallback_counts: BTreeMap<String, (ChunkCoordinate, u32)> = BTreeMap::new();
    for z in 0..size_z {
        for x in 0..size_x {
            for y in min_y..=max_y {
                cancel.check()?;
                if let Some(state) = n.block(origin_x + x, y, origin_z + z) {
                    let c = classify(state);
                    if let Some(layer) = c.layer {
                        *block_counts
                            .entry((layer, c.rgb, c.alpha.to_bits()))
                            .or_default() += 1;
                        if layer == RenderLayer::Fallback {
                            let coordinate = ChunkCoordinate {
                                x: (origin_x + x).div_euclid(16),
                                z: (origin_z + z).div_euclid(16),
                            };
                            let entry = fallback_counts
                                .entry(state.name.clone())
                                .or_insert((coordinate, 0));
                            entry.1 += 1;
                        }
                    }
                }
            }
        }
    }
    let mut rect_count = 0_usize;
    for direction in [Direction::PosX, Direction::NegX] {
        for x in 0..size_x {
            cancel.check()?;
            let m = mask(size_z, size_y, |z, dy| {
                face_key(n, origin_x + x, min_y + dy, origin_z + z, direction)
            });
            merge(
                m,
                MergeSpec {
                    width: size_z,
                    height: size_y,
                    direction,
                    fixed: x,
                    u_offset: 0,
                    v_offset: min_y,
                },
                &mut groups,
                &mut rect_count,
                cancel,
            )?;
        }
    }
    for direction in [Direction::PosY, Direction::NegY] {
        for dy in 0..size_y {
            cancel.check()?;
            let m = mask(size_x, size_z, |x, z| {
                face_key(n, origin_x + x, min_y + dy, origin_z + z, direction)
            });
            merge(
                m,
                MergeSpec {
                    width: size_x,
                    height: size_z,
                    direction,
                    fixed: min_y + dy,
                    u_offset: 0,
                    v_offset: 0,
                },
                &mut groups,
                &mut rect_count,
                cancel,
            )?;
        }
    }
    for direction in [Direction::PosZ, Direction::NegZ] {
        for z in 0..size_z {
            cancel.check()?;
            let m = mask(size_x, size_y, |x, dy| {
                face_key(n, origin_x + x, min_y + dy, origin_z + z, direction)
            });
            merge(
                m,
                MergeSpec {
                    width: size_x,
                    height: size_y,
                    direction,
                    fixed: z,
                    u_offset: 0,
                    v_offset: min_y,
                },
                &mut groups,
                &mut rect_count,
                cancel,
            )?;
        }
    }
    warnings.extend(
        fallback_counts
            .into_iter()
            .map(|(block_name, (coordinate, occurrences))| MeshWarning {
                kind: MeshWarningKind::FallbackBlock,
                coordinate,
                failure: None,
                block_name: Some(block_name),
                occurrences,
            }),
    );
    let mut out = MeshData {
        origin_world_x: origin_x,
        origin_world_z: origin_z,
        block_count: block_counts.values().copied().sum(),
        warnings,
        ..MeshData::default()
    };
    // Fully occluded materials still contribute blocks to the selection total.
    // Retain their zero-face batches so IPC batch totals remain consistent.
    for key in block_counts.keys() {
        groups.entry(*key).or_default();
    }
    if groups.len() > MAX_MESH_BATCHES {
        return Err(CoreError::ResourceLimit);
    }
    for (key, rects) in groups {
        let first = out.indices.len() as u32;
        for r in &rects {
            cancel.check()?;
            emit(&mut out, *r)?;
        }
        let count = out.indices.len() as u32 - first;
        out.face_count += rects.len() as u32;
        out.batches.push(MeshBatch {
            layer: key.0,
            rgb: key.1,
            alpha: f32::from_bits(key.2),
            first_index: first,
            index_count: count,
            block_count: block_counts.get(&key).copied().unwrap_or(0),
            face_count: rects.len() as u32,
        });
    }
    out.bounds = bounds(&out.positions);
    Ok(out)
}

fn halo_coordinates(rect: ChunkRect) -> BTreeSet<ChunkCoordinate> {
    let mut coordinates = BTreeSet::new();
    let end_x = rect.min_x + i32::from(rect.width);
    let end_z = rect.min_z + i32::from(rect.depth);
    for z in rect.min_z..end_z {
        coordinates.insert(ChunkCoordinate {
            x: rect.min_x - 1,
            z,
        });
        coordinates.insert(ChunkCoordinate { x: end_x, z });
    }
    for x in rect.min_x..end_x {
        coordinates.insert(ChunkCoordinate {
            x,
            z: rect.min_z - 1,
        });
        coordinates.insert(ChunkCoordinate { x, z: end_z });
    }
    coordinates
}
fn mask(width: i32, height: i32, mut f: impl FnMut(i32, i32) -> Option<Key>) -> Vec<Option<Key>> {
    let mut out = Vec::with_capacity((width * height) as usize);
    for v in 0..height {
        for u in 0..width {
            out.push(f(u, v));
        }
    }
    out
}
fn merge(
    mut m: Vec<Option<Key>>,
    spec: MergeSpec,
    groups: &mut BTreeMap<Key, Vec<Rect>>,
    rect_count: &mut usize,
    cancel: &dyn Cancellation,
) -> Result<(), CoreError> {
    let MergeSpec {
        width,
        height,
        direction,
        fixed,
        u_offset,
        v_offset,
    } = spec;
    for v in 0..height {
        cancel.check()?;
        let mut u = 0;
        while u < width {
            let at = (v * width + u) as usize;
            let Some(key) = m[at] else {
                u += 1;
                continue;
            };
            let mut w = 1;
            while u + w < width && m[(v * width + u + w) as usize] == Some(key) {
                w += 1;
            }
            let mut h = 1;
            'height: while v + h < height {
                for dx in 0..w {
                    if m[((v + h) * width + u + dx) as usize] != Some(key) {
                        break 'height;
                    }
                }
                h += 1;
            }
            for dy in 0..h {
                for dx in 0..w {
                    m[((v + dy) * width + u + dx) as usize] = None;
                }
            }
            if *rect_count >= MAX_MESH_FACES {
                return Err(CoreError::ResourceLimit);
            }
            if !groups.contains_key(&key) && groups.len() >= MAX_MESH_BATCHES {
                return Err(CoreError::ResourceLimit);
            }
            groups.entry(key).or_default().push(Rect {
                direction,
                fixed,
                u: u + u_offset,
                v: v + v_offset,
                width: w,
                height: h,
            });
            *rect_count += 1;
            u += w;
        }
    }
    Ok(())
}
fn face_key(n: &Neighborhood, x: i32, y: i32, z: i32, d: Direction) -> Option<Key> {
    let current_state = n.block(x, y, z)?;
    let current = classify(current_state);
    let layer = current.layer?;
    let (dx, dy, dz) = delta(d);
    let neighbor = n
        .block(x + dx, y + dy, z + dz)
        .map(|state| (state, classify(state)));
    visible(current_state, current, neighbor).then_some((
        layer,
        current.rgb,
        current.alpha.to_bits(),
    ))
}
fn delta(d: Direction) -> (i32, i32, i32) {
    match d {
        Direction::PosX => (1, 0, 0),
        Direction::NegX => (-1, 0, 0),
        Direction::PosY => (0, 1, 0),
        Direction::NegY => (0, -1, 0),
        Direction::PosZ => (0, 0, 1),
        Direction::NegZ => (0, 0, -1),
    }
}
fn visible(
    current_state: &BlockState,
    current: RenderClass,
    neighbor: Option<(&BlockState, RenderClass)>,
) -> bool {
    let Some((neighbor_state, neighbor)) = neighbor else {
        return true;
    };
    if current.layer == Some(RenderLayer::Fluid) {
        return neighbor.layer.is_none();
    }
    if neighbor.layer.is_none() || neighbor.layer == Some(RenderLayer::Fluid) {
        return true;
    }
    if matches!(
        current.layer,
        Some(RenderLayer::Opaque | RenderLayer::Fallback)
    ) {
        !matches!(
            neighbor.layer,
            Some(RenderLayer::Opaque | RenderLayer::Fallback)
        )
    } else {
        current_state != neighbor_state
    }
}
fn emit(out: &mut MeshData, r: Rect) -> Result<(), CoreError> {
    if out.positions.len() / 3 + 4 > MAX_MESH_VERTICES || out.indices.len() + 6 > MAX_MESH_INDICES {
        return Err(CoreError::ResourceLimit);
    }
    let f = r.fixed as f32;
    let u = r.u as f32;
    let v = r.v as f32;
    let w = r.width as f32;
    let h = r.height as f32;
    let (q, n) = match r.direction {
        Direction::PosX => (
            [
                f + 1.,
                v,
                u,
                f + 1.,
                v + h,
                u,
                f + 1.,
                v + h,
                u + w,
                f + 1.,
                v,
                u + w,
            ],
            [1., 0., 0.],
        ),
        Direction::NegX => (
            [f, v, u + w, f, v + h, u + w, f, v + h, u, f, v, u],
            [-1., 0., 0.],
        ),
        Direction::PosY => (
            [
                u,
                f + 1.,
                v + h,
                u + w,
                f + 1.,
                v + h,
                u + w,
                f + 1.,
                v,
                u,
                f + 1.,
                v,
            ],
            [0., 1., 0.],
        ),
        Direction::NegY => (
            [u, f, v, u + w, f, v, u + w, f, v + h, u, f, v + h],
            [0., -1., 0.],
        ),
        Direction::PosZ => (
            [
                u + w,
                v,
                f + 1.,
                u + w,
                v + h,
                f + 1.,
                u,
                v + h,
                f + 1.,
                u,
                v,
                f + 1.,
            ],
            [0., 0., 1.],
        ),
        Direction::NegZ => (
            [u, v, f, u, v + h, f, u + w, v + h, f, u + w, v, f],
            [0., 0., -1.],
        ),
    };
    let base = (out.positions.len() / 3) as u32;
    out.positions.extend_from_slice(&q);
    for _ in 0..4 {
        out.normals.extend_from_slice(&n);
    }
    out.indices
        .extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    Ok(())
}

fn bounds(positions: &[f32]) -> Option<MeshBounds> {
    let (points, remainder) = positions.as_chunks::<3>();
    debug_assert!(remainder.is_empty());
    let mut points = points.iter();
    let first = points.next()?;
    let mut bounds = MeshBounds {
        min_x: first[0],
        min_y: first[1],
        min_z: first[2],
        max_x: first[0],
        max_y: first[1],
        max_z: first[2],
    };
    for point in points {
        bounds.min_x = bounds.min_x.min(point[0]);
        bounds.min_y = bounds.min_y.min(point[1]);
        bounds.min_z = bounds.min_z.min(point[2]);
        bounds.max_x = bounds.max_x.max(point[0]);
        bounds.max_y = bounds.max_y.max(point[1]);
        bounds.max_z = bounds.max_z.max(point[2]);
    }
    Some(bounds)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mesh_limits_fit_under_the_response_budget() {
        assert_eq!(MAX_MESH_VERTICES, MAX_MESH_FACES * 4);
        assert_eq!(MAX_MESH_INDICES, MAX_MESH_FACES * 6);
        let array_bytes = MAX_MESH_VERTICES * 2 * size_of::<f32>()
            + MAX_MESH_INDICES * size_of::<u32>()
            + MAX_MESH_BATCHES * 8 * size_of::<u32>();
        assert!(array_bytes < crate::limits::MAX_RESPONSE_BYTES);
    }
}
