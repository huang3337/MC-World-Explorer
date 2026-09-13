use crate::{cancel::Cancellation, limits::MAX_NBT_SEQUENCE, CoreError};
use flate2::read::{GzDecoder, ZlibDecoder};
use serde::de::DeserializeOwned;
use std::io::{Cursor, Read};

pub(crate) fn read_limited(
    mut reader: impl Read,
    max: usize,
    cancel: &dyn Cancellation,
) -> Result<Vec<u8>, CoreError> {
    let mut output = Vec::new();
    let mut buffer = [0_u8; 8192];
    loop {
        cancel.check()?;
        let count = reader.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        if output.len().checked_add(count).is_none_or(|n| n > max) {
            return Err(CoreError::ResourceLimit);
        }
        output.extend_from_slice(&buffer[..count]);
    }
    Ok(output)
}

pub(crate) fn decode_nbt<T: DeserializeOwned>(
    input: &[u8],
    max_output: usize,
    cancel: &dyn Cancellation,
) -> Result<T, CoreError> {
    cancel.check()?;
    let decoded = if input.starts_with(&[0x1f, 0x8b]) {
        inflate(GzDecoder::new(Cursor::new(input)), max_output, cancel)?
    } else if is_zlib(input) {
        inflate(ZlibDecoder::new(Cursor::new(input)), max_output, cancel)?
    } else {
        if input.len() > max_output {
            return Err(CoreError::ResourceLimit);
        }
        input.to_vec()
    };
    cancel.check()?;
    super::preflight::validate(&decoded, cancel)?;
    fastnbt::from_bytes_with_opts(
        &decoded,
        fastnbt::DeOpts::new().max_seq_len(MAX_NBT_SEQUENCE),
    )
    .map_err(|_| CoreError::InvalidNbt)
}

fn is_zlib(input: &[u8]) -> bool {
    input.len() >= 2
        && input[0] & 0x0f == 8
        && input[0] >> 4 <= 7
        && u16::from_be_bytes([input[0], input[1]]).is_multiple_of(31)
}

fn inflate(reader: impl Read, max: usize, cancel: &dyn Cancellation) -> Result<Vec<u8>, CoreError> {
    match read_limited(reader, max, cancel) {
        Ok(bytes) => Ok(bytes),
        Err(error @ (CoreError::ResourceLimit | CoreError::Cancelled)) => Err(error),
        Err(_) => Err(CoreError::InvalidNbt),
    }
}
