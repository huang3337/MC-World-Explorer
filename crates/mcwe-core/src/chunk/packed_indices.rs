use crate::CoreError;

pub(crate) fn unpack(data: &[i64], palette_len: usize) -> Result<Vec<u16>, CoreError> {
    if palette_len == 0 {
        return Err(CoreError::InvalidChunk);
    }
    if palette_len == 1 {
        if !data.is_empty() {
            return Err(CoreError::InvalidChunk);
        }
        return Ok(vec![0; 4096]);
    }
    let bits = usize::max(
        4,
        usize::BITS as usize - (palette_len - 1).leading_zeros() as usize,
    );
    let per_long = 64 / bits;
    let padded = 4096_usize.div_ceil(per_long);
    let compact = (4096 * bits).div_ceil(64);
    if data.len() == padded {
        unpack_padded(data, bits, per_long, palette_len)
    } else if data.len() == compact {
        unpack_compact(data, bits, palette_len)
    } else {
        Err(CoreError::InvalidChunk)
    }
}
fn unpack_padded(
    data: &[i64],
    bits: usize,
    per_long: usize,
    palette_len: usize,
) -> Result<Vec<u16>, CoreError> {
    let mask = (1_u64 << bits) - 1;
    let mut out = Vec::with_capacity(4096);
    for i in 0..4096 {
        let value = ((data[i / per_long] as u64 >> (i % per_long * bits)) & mask) as usize;
        if value >= palette_len {
            return Err(CoreError::InvalidChunk);
        }
        out.push(value as u16);
    }
    Ok(out)
}
fn unpack_compact(data: &[i64], bits: usize, palette_len: usize) -> Result<Vec<u16>, CoreError> {
    let mask = (1_u64 << bits) - 1;
    let mut out = Vec::with_capacity(4096);
    for i in 0..4096 {
        let bit = i * bits;
        let word = bit / 64;
        let shift = bit % 64;
        let mut value = (data[word] as u64) >> shift;
        if shift + bits > 64 {
            value |= (data[word + 1] as u64) << (64 - shift);
        }
        let value = (value & mask) as usize;
        if value >= palette_len {
            return Err(CoreError::InvalidChunk);
        }
        out.push(value as u16);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn single_palette_requires_no_data() {
        assert_eq!(unpack(&[], 1).unwrap().len(), 4096);
        assert!(unpack(&[0], 1).is_err());
    }
    #[test]
    fn padded_four_bit_values_decode() {
        let mut data = vec![0_i64; 256];
        data[0] = 0x10;
        let out = unpack(&data, 2).unwrap();
        assert_eq!(&out[..3], [0, 1, 0]);
    }
    #[test]
    fn rejects_wrong_length_and_palette_index() {
        assert!(unpack(&[0], 2).is_err());
        let mut data = vec![0_i64; 256];
        data[0] = 2;
        assert!(unpack(&data, 2).is_err());
    }
}
