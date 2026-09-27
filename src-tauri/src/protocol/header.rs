use mcwe_core::{
    limits::{MAX_RESPONSE_BYTES, MAX_TOTAL_TEXT_BYTES},
    CoreError,
};
use std::collections::BTreeSet;

pub(super) struct Section {
    pub id: u16,
    pub scalar: u16,
    pub count: u32,
    pub bytes: Vec<u8>,
}
fn align4(value: usize) -> Result<usize, CoreError> {
    value
        .checked_add(3)
        .map(|n| n & !3)
        .ok_or(CoreError::ResourceLimit)
}
pub(super) fn encode(
    kind: u16,
    sections: Vec<Section>,
    metadata: &str,
) -> Result<Vec<u8>, CoreError> {
    if metadata.len() > MAX_TOTAL_TEXT_BYTES {
        return Err(CoreError::ResourceLimit);
    }
    validate_sections(kind, &sections)?;
    let table_len = sections
        .len()
        .checked_mul(16)
        .ok_or(CoreError::ResourceLimit)?;
    let mut cursor = align4(
        32_usize
            .checked_add(table_len)
            .ok_or(CoreError::ResourceLimit)?,
    )?;
    let mut offsets = Vec::with_capacity(sections.len());
    for s in &sections {
        offsets.push(cursor);
        cursor = align4(
            cursor
                .checked_add(s.bytes.len())
                .ok_or(CoreError::ResourceLimit)?,
        )?;
    }
    let metadata_offset = cursor;
    let total = metadata_offset
        .checked_add(metadata.len())
        .ok_or(CoreError::ResourceLimit)?;
    if total > MAX_RESPONSE_BYTES {
        return Err(CoreError::ResourceLimit);
    }
    let total_u32 = u32::try_from(total).map_err(|_| CoreError::ResourceLimit)?;
    let metadata_offset_u32 =
        u32::try_from(metadata_offset).map_err(|_| CoreError::ResourceLimit)?;
    let metadata_len_u32 = u32::try_from(metadata.len()).map_err(|_| CoreError::ResourceLimit)?;
    let section_count_u32 = u32::try_from(sections.len()).map_err(|_| CoreError::ResourceLimit)?;
    let mut out = vec![0_u8; total];
    out[0..4].copy_from_slice(b"MCWE");
    put16(&mut out, 4, 1);
    put16(&mut out, 6, kind);
    put32(&mut out, 8, 32);
    put32(&mut out, 12, total_u32);
    put32(&mut out, 16, metadata_offset_u32);
    put32(&mut out, 20, metadata_len_u32);
    put32(&mut out, 24, 32);
    put32(&mut out, 28, section_count_u32);
    for (i, (s, offset)) in sections.iter().zip(offsets).enumerate() {
        let base = 32 + i * 16;
        let offset_u32 = u32::try_from(offset).map_err(|_| CoreError::ResourceLimit)?;
        let byte_len_u32 = u32::try_from(s.bytes.len()).map_err(|_| CoreError::ResourceLimit)?;
        put16(&mut out, base, s.id);
        put16(&mut out, base + 2, s.scalar);
        put32(&mut out, base + 4, offset_u32);
        put32(&mut out, base + 8, byte_len_u32);
        put32(&mut out, base + 12, s.count);
        out[offset..offset + s.bytes.len()].copy_from_slice(&s.bytes);
    }
    out[metadata_offset..].copy_from_slice(metadata.as_bytes());
    Ok(out)
}

fn validate_sections(kind: u16, sections: &[Section]) -> Result<(), CoreError> {
    let expected: &[(u16, u16)] = match kind {
        1 => &[(1, 4), (2, 5), (3, 1)],
        2 => &[(10, 6), (11, 6), (12, 4), (13, 4)],
        3 => &[(1, 4), (2, 5), (3, 1)],
        4 => &[(20, 4), (21, 4), (22, 5), (23, 1)],
        _ => return Err(CoreError::InvalidChunk),
    };
    if sections.len() != expected.len() {
        return Err(CoreError::InvalidChunk);
    }
    let mut ids = BTreeSet::new();
    for (section, &(expected_id, expected_scalar)) in sections.iter().zip(expected) {
        if section.id != expected_id || section.scalar != expected_scalar || !ids.insert(section.id)
        {
            return Err(CoreError::InvalidChunk);
        }
        let width = scalar_width(section.scalar).ok_or(CoreError::InvalidChunk)?;
        let expected_len = usize::try_from(section.count)
            .map_err(|_| CoreError::ResourceLimit)?
            .checked_mul(width)
            .ok_or(CoreError::ResourceLimit)?;
        if section.bytes.len() != expected_len {
            return Err(CoreError::InvalidChunk);
        }
    }
    Ok(())
}

fn scalar_width(scalar: u16) -> Option<usize> {
    match scalar {
        1 | 2 => Some(1),
        3 => Some(2),
        4..=6 => Some(4),
        _ => None,
    }
}
fn put16(out: &mut [u8], at: usize, v: u16) {
    out[at..at + 2].copy_from_slice(&v.to_le_bytes())
}
fn put32(out: &mut [u8], at: usize, v: u32) {
    out[at..at + 4].copy_from_slice(&v.to_le_bytes())
}
pub(super) fn u32_bytes(values: impl ExactSizeIterator<Item = u32>) -> Result<Vec<u8>, CoreError> {
    numeric_bytes(values, u32::to_le_bytes)
}
pub(super) fn i32_bytes(values: impl ExactSizeIterator<Item = i32>) -> Result<Vec<u8>, CoreError> {
    numeric_bytes(values, i32::to_le_bytes)
}
pub(super) fn f32_bytes(values: impl ExactSizeIterator<Item = f32>) -> Result<Vec<u8>, CoreError> {
    let byte_len = values
        .len()
        .checked_mul(4)
        .ok_or(CoreError::ResourceLimit)?;
    let mut out = Vec::with_capacity(byte_len);
    for v in values {
        if !v.is_finite() {
            return Err(CoreError::InvalidChunk);
        }
        out.extend_from_slice(&v.to_le_bytes());
    }
    Ok(out)
}

fn numeric_bytes<T, const N: usize>(
    values: impl ExactSizeIterator<Item = T>,
    encode_value: impl Fn(T) -> [u8; N],
) -> Result<Vec<u8>, CoreError> {
    let byte_len = values
        .len()
        .checked_mul(N)
        .ok_or(CoreError::ResourceLimit)?;
    let mut out = Vec::with_capacity(byte_len);
    for value in values {
        out.extend_from_slice(&encode_value(value));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn header_has_exact_lengths() {
        let bytes = encode(
            1,
            vec![
                Section {
                    id: 1,
                    scalar: 4,
                    count: 0,
                    bytes: vec![],
                },
                Section {
                    id: 2,
                    scalar: 5,
                    count: 0,
                    bytes: vec![],
                },
                Section {
                    id: 3,
                    scalar: 1,
                    count: 0,
                    bytes: vec![],
                },
            ],
            "{}",
        )
        .unwrap();
        assert_eq!(&bytes[..4], b"MCWE");
        assert_eq!(
            u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize,
            bytes.len()
        );
    }

    #[test]
    fn accepts_utf8_metadata_and_rejects_scalar_length_mismatch() {
        let sections = || {
            vec![
                Section {
                    id: 1,
                    scalar: 4,
                    count: 0,
                    bytes: vec![],
                },
                Section {
                    id: 2,
                    scalar: 5,
                    count: 0,
                    bytes: vec![],
                },
                Section {
                    id: 3,
                    scalar: 1,
                    count: 0,
                    bytes: vec![],
                },
            ]
        };
        assert!(encode(1, sections(), r#"{"warning":"区块缺失"}"#).is_ok());
        let mut invalid = sections();
        invalid[0].count = 1;
        assert!(matches!(
            encode(1, invalid, "{}"),
            Err(CoreError::InvalidChunk)
        ));
        let oversized = "x".repeat(MAX_TOTAL_TEXT_BYTES + 1);
        assert!(matches!(
            encode(1, sections(), &oversized),
            Err(CoreError::ResourceLimit)
        ));
    }
}
