use crate::{
    cancel::Cancellation,
    limits::{MAX_NBT_DEPTH, MAX_NBT_SEQUENCE, MAX_NBT_TAGS, MAX_NBT_TEXT_BYTES},
    CoreError,
};

pub(super) fn validate(bytes: &[u8], cancel: &dyn Cancellation) -> Result<(), CoreError> {
    let mut cursor = Cursor {
        bytes,
        position: 0,
        tags: 0,
        text_bytes: 0,
        cancel,
    };
    if cursor.byte()? != 10 {
        return Err(CoreError::InvalidNbt);
    }
    cursor.string()?;
    cursor.payload(10, 1)?;
    if cursor.position != bytes.len() {
        return Err(CoreError::InvalidNbt);
    }
    Ok(())
}

struct Cursor<'a> {
    bytes: &'a [u8],
    position: usize,
    tags: usize,
    text_bytes: usize,
    cancel: &'a dyn Cancellation,
}

impl Cursor<'_> {
    fn payload(&mut self, tag: u8, depth: usize) -> Result<(), CoreError> {
        if depth > MAX_NBT_DEPTH {
            return Err(CoreError::ResourceLimit);
        }
        self.count_tags(1)?;
        match tag {
            1 => self.skip(1),
            2 => self.skip(2),
            3 | 5 => self.skip(4),
            4 | 6 => self.skip(8),
            7 => {
                let len = self.sequence_len()?;
                self.skip(len)
            }
            8 => self.string(),
            9 => {
                let element = self.byte()?;
                let len = self.sequence_len()?;
                if element == 0 && len != 0 {
                    return Err(CoreError::InvalidNbt);
                }
                if !(0..=12).contains(&element) {
                    return Err(CoreError::InvalidNbt);
                }
                self.count_tags(len)?;
                for index in 0..len {
                    if index % 1024 == 0 {
                        self.cancel.check()?;
                    }
                    self.payload_without_count(element, depth + 1)?;
                }
                Ok(())
            }
            10 => {
                loop {
                    self.cancel.check()?;
                    let child = self.byte()?;
                    if child == 0 {
                        break;
                    }
                    if child > 12 {
                        return Err(CoreError::InvalidNbt);
                    }
                    self.string()?;
                    self.payload(child, depth + 1)?;
                }
                Ok(())
            }
            11 => {
                let len = self.sequence_len()?;
                self.skip(len.checked_mul(4).ok_or(CoreError::ResourceLimit)?)
            }
            12 => {
                let len = self.sequence_len()?;
                self.skip(len.checked_mul(8).ok_or(CoreError::ResourceLimit)?)
            }
            _ => Err(CoreError::InvalidNbt),
        }
    }

    fn payload_without_count(&mut self, tag: u8, depth: usize) -> Result<(), CoreError> {
        self.tags = self.tags.checked_sub(1).ok_or(CoreError::InvalidNbt)?;
        self.payload(tag, depth)
    }

    fn count_tags(&mut self, count: usize) -> Result<(), CoreError> {
        self.tags = self
            .tags
            .checked_add(count)
            .ok_or(CoreError::ResourceLimit)?;
        if self.tags > MAX_NBT_TAGS {
            return Err(CoreError::ResourceLimit);
        }
        Ok(())
    }

    fn sequence_len(&mut self) -> Result<usize, CoreError> {
        let raw = i32::from_be_bytes(
            self.take(4)?
                .try_into()
                .map_err(|_| CoreError::InvalidNbt)?,
        );
        let len = usize::try_from(raw).map_err(|_| CoreError::InvalidNbt)?;
        if len > MAX_NBT_SEQUENCE {
            return Err(CoreError::ResourceLimit);
        }
        Ok(len)
    }

    fn string(&mut self) -> Result<(), CoreError> {
        let len = usize::from(u16::from_be_bytes(
            self.take(2)?
                .try_into()
                .map_err(|_| CoreError::InvalidNbt)?,
        ));
        self.text_bytes = self
            .text_bytes
            .checked_add(len)
            .ok_or(CoreError::ResourceLimit)?;
        if self.text_bytes > MAX_NBT_TEXT_BYTES {
            return Err(CoreError::ResourceLimit);
        }
        self.skip(len)
    }

    fn byte(&mut self) -> Result<u8, CoreError> {
        Ok(self.take(1)?[0])
    }
    fn skip(&mut self, len: usize) -> Result<(), CoreError> {
        self.take(len).map(|_| ())
    }
    fn take(&mut self, len: usize) -> Result<&[u8], CoreError> {
        let end = self
            .position
            .checked_add(len)
            .ok_or(CoreError::ResourceLimit)?;
        let value = self
            .bytes
            .get(self.position..end)
            .ok_or(CoreError::InvalidNbt)?;
        self.position = end;
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cancel::NeverCancel;

    #[test]
    fn rejects_negative_and_oversized_sequence_lengths() {
        let negative = [10, 0, 0, 7, 0, 1, b'x', 0xff, 0xff, 0xff, 0xff, 0];
        assert!(matches!(
            validate(&negative, &NeverCancel),
            Err(CoreError::InvalidNbt)
        ));

        let mut oversized = vec![10, 0, 0, 9, 0, 1, b'x', 1];
        oversized.extend_from_slice(&((MAX_NBT_SEQUENCE as u32) + 1).to_be_bytes());
        assert!(matches!(
            validate(&oversized, &NeverCancel),
            Err(CoreError::ResourceLimit)
        ));
    }

    #[test]
    fn rejects_truncated_arrays_and_trailing_data() {
        let truncated = [10, 0, 0, 11, 0, 1, b'x', 0, 0, 0, 1, 0, 0];
        assert!(matches!(
            validate(&truncated, &NeverCancel),
            Err(CoreError::InvalidNbt)
        ));
        assert!(matches!(
            validate(&[10, 0, 0, 0, 0], &NeverCancel),
            Err(CoreError::InvalidNbt)
        ));
    }

    #[test]
    fn accepts_compounds_nested_in_a_list() {
        let bytes = [
            10, 0, 0, // root compound
            9, 0, 1, b'x', 10, 0, 0, 0, 1, // one compound in a list
            1, 0, 1, b'y', 7, 0, 0, // byte child, compound end, root end
        ];
        validate(&bytes, &NeverCancel).unwrap();
    }
}
