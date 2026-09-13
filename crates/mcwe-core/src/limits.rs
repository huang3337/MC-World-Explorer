pub const LEVEL_INPUT_BYTES: usize = 16 * 1024 * 1024;
pub const NBT_OUTPUT_BYTES: usize = 64 * 1024 * 1024;
pub const CHUNK_INPUT_BYTES: usize = 1024 * 1024;
// Section Y is represented by a signed byte in both supported layouts.  The
// structural maximum is therefore 256 distinct section coordinates; a smaller
// arbitrary cap rejected valid modded worlds that the Java baseline accepts.
pub const MAX_SECTIONS: usize = 256;
pub const MAX_PALETTE: usize = 4096;
pub const MAX_TEXT_BYTES: usize = 1024;
pub const MAX_TOTAL_TEXT_BYTES: usize = 256 * 1024;
// Preflight scans the already bounded decompressed NBT payload. Counting every
// tag name and skipped string against the IPC/palette text budget made normal
// level.dat files fail even though their total bytes were within NBT_OUTPUT_BYTES.
pub const MAX_NBT_TEXT_BYTES: usize = NBT_OUTPUT_BYTES;
pub const MAX_NBT_DEPTH: usize = 64;
pub const MAX_NBT_SEQUENCE: usize = 1_000_000;
pub const MAX_NBT_TAGS: usize = 1_000_000;
// Legacy v1 golden-vector dimensions; production uses PREVIEW_SIDE.
pub const SURFACE_SIDE: usize = 144;
pub const PREVIEW_SIDE: usize = 1024;
pub const MAX_TARGET_SIDE: u8 = 8;
pub const SURFACE_CELLS: usize = SURFACE_SIDE * SURFACE_SIDE;
pub const MAX_RESPONSE_BYTES: usize = 128 * 1024 * 1024;
pub const MAX_MESH_FACES: usize = 1_000_000;
pub const MAX_MESH_VERTICES: usize = MAX_MESH_FACES * 4;
pub const MAX_MESH_INDICES: usize = MAX_MESH_FACES * 6;
pub const MAX_MESH_BATCHES: usize = 4096;
