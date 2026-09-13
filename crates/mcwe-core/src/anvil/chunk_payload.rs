#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkCompression {
    Gzip,
    Zlib,
    Uncompressed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkPayload {
    pub compression: ChunkCompression,
    pub bytes: Vec<u8>,
}
