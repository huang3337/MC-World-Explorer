mod header;
mod map_tile_v1;
mod mesh_v1;
mod surface_v1;

#[cfg(test)]
mod tests;

pub use map_tile_v1::encode_map_tile_v1;
pub use mesh_v1::encode_mesh_v1;
pub use surface_v1::encode_surface_v1;

/// JSON number 可无损表达的最大正整数；所有跨 IPC 的应用内编号不得超过此值。
pub(crate) const MAX_SAFE_INTEGER_ID: u64 = (1_u64 << 53) - 1;
