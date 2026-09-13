use super::{ChunkFailure, RenderLayer};
use crate::anvil::ChunkCoordinate;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MeshBounds {
    pub min_x: f32,
    pub min_y: f32,
    pub min_z: f32,
    pub max_x: f32,
    pub max_y: f32,
    pub max_z: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum MeshWarningKind {
    TargetUnavailable,
    NeighborUnavailable,
    FallbackBlock,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MeshWarning {
    pub kind: MeshWarningKind,
    pub coordinate: ChunkCoordinate,
    pub failure: Option<ChunkFailure>,
    pub block_name: Option<String>,
    pub occurrences: u32,
}
#[derive(Debug, Clone, PartialEq)]
pub struct MeshBatch {
    pub layer: RenderLayer,
    pub rgb: u32,
    pub alpha: f32,
    pub first_index: u32,
    pub index_count: u32,
    pub block_count: u32,
    pub face_count: u32,
}
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MeshData {
    pub origin_world_x: i32,
    pub origin_world_z: i32,
    pub bounds: Option<MeshBounds>,
    pub positions: Vec<f32>,
    pub normals: Vec<f32>,
    pub indices: Vec<u32>,
    pub batches: Vec<MeshBatch>,
    pub block_count: u32,
    pub face_count: u32,
    pub warnings: Vec<MeshWarning>,
}
