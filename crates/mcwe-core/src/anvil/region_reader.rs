use super::{ChunkCompression, ChunkCoordinate, ChunkPayload};
use crate::{
    cancel::Cancellation,
    limits::{CHUNK_INPUT_BYTES, NBT_OUTPUT_BYTES},
    nbt::read_limited,
    CoreError, WorldSource,
};
use flate2::read::{GzDecoder, ZlibDecoder};
use std::io::{Cursor, Read, Seek, SeekFrom};

pub struct RegionReader {
    file: crate::WorldFile,
    file_len: u64,
    region_x: i32,
    region_z: i32,
}

impl RegionReader {
    pub fn open(
        source: &WorldSource,
        region_x: i32,
        region_z: i32,
    ) -> Result<Option<Self>, CoreError> {
        let path = format!("region/r.{region_x}.{region_z}.mca");
        let mut file = match source.open_file(path) {
            Err(CoreError::NotFound) => return Ok(None),
            other => other?,
        };
        let file_len = file.seek(SeekFrom::End(0))?;
        if file_len < 8192 {
            return Err(CoreError::InvalidRegion);
        }
        Ok(Some(Self {
            file,
            file_len,
            region_x,
            region_z,
        }))
    }

    pub fn read_chunk_payload(
        &mut self,
        coordinate: ChunkCoordinate,
        cancel: &dyn Cancellation,
    ) -> Result<Option<ChunkPayload>, CoreError> {
        cancel.check()?;
        if coordinate.region() != (self.region_x, self.region_z) {
            return Err(CoreError::InvalidRegion);
        }
        read_from_file(&mut self.file, self.file_len, coordinate, cancel)
    }
}

pub fn read_chunk_payload(
    source: &WorldSource,
    coordinate: ChunkCoordinate,
    cancel: &dyn Cancellation,
) -> Result<Option<ChunkPayload>, CoreError> {
    cancel.check()?;
    let (rx, rz) = coordinate.region();
    let Some(mut region) = RegionReader::open(source, rx, rz)? else {
        return Ok(None);
    };
    region.read_chunk_payload(coordinate, cancel)
}

fn read_from_file(
    file: &mut crate::WorldFile,
    file_len: u64,
    coordinate: ChunkCoordinate,
    cancel: &dyn Cancellation,
) -> Result<Option<ChunkPayload>, CoreError> {
    let header_pos = u64::try_from(coordinate.slot() * 4).map_err(|_| CoreError::InvalidRegion)?;
    file.seek(SeekFrom::Start(header_pos))?;
    let mut location = [0_u8; 4];
    file.read_exact(&mut location)?;
    let sector_offset =
        (u32::from(location[0]) << 16) | (u32::from(location[1]) << 8) | u32::from(location[2]);
    let sector_count = u32::from(location[3]);
    if sector_offset == 0 && sector_count == 0 {
        return Ok(None);
    }
    if sector_offset < 2 || sector_count == 0 {
        return Err(CoreError::InvalidRegion);
    }
    let start = u64::from(sector_offset)
        .checked_mul(4096)
        .ok_or(CoreError::InvalidRegion)?;
    let allocated = u64::from(sector_count)
        .checked_mul(4096)
        .ok_or(CoreError::InvalidRegion)?;
    let end = start
        .checked_add(allocated)
        .ok_or(CoreError::InvalidRegion)?;
    if end > file_len {
        return Err(CoreError::InvalidRegion);
    }
    file.seek(SeekFrom::Start(start))?;
    let mut prefix = [0_u8; 5];
    file.read_exact(&mut prefix)?;
    let length = u32::from_be_bytes([prefix[0], prefix[1], prefix[2], prefix[3]]);
    if length < 1
        || u64::from(length)
            .checked_add(4)
            .is_none_or(|n| n > allocated)
    {
        return Err(CoreError::InvalidRegion);
    }
    let compression_byte = prefix[4];
    if compression_byte & 0x80 != 0 {
        return Err(CoreError::UnsupportedCompression);
    }
    let compression = match compression_byte {
        1 => ChunkCompression::Gzip,
        2 => ChunkCompression::Zlib,
        3 => ChunkCompression::Uncompressed,
        _ => return Err(CoreError::UnsupportedCompression),
    };
    let compressed_len = usize::try_from(length - 1).map_err(|_| CoreError::ResourceLimit)?;
    if compressed_len > CHUNK_INPUT_BYTES {
        return Err(CoreError::ResourceLimit);
    }
    let mut compressed = vec![0_u8; compressed_len];
    file.read_exact(&mut compressed)?;
    cancel.check()?;
    let bytes = match compression {
        ChunkCompression::Gzip => read_limited(
            GzDecoder::new(Cursor::new(compressed)),
            NBT_OUTPUT_BYTES,
            cancel,
        )?,
        ChunkCompression::Zlib => read_limited(
            ZlibDecoder::new(Cursor::new(compressed)),
            NBT_OUTPUT_BYTES,
            cancel,
        )?,
        ChunkCompression::Uncompressed => compressed,
    };
    Ok(Some(ChunkPayload { compression, bytes }))
}
