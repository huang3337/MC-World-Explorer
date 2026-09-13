use crate::{limits::PREVIEW_SIDE, CoreError};

/// Java PreviewGenerator.boundsFor: exact block-centred window, not chunk-centred.
#[derive(Debug, Clone, Copy)]
pub struct PreviewBounds {
    pub min_x: i32,
    pub min_z: i32,
    pub max_x: i32,
    pub max_z: i32,
}
impl PreviewBounds {
    pub fn new(x: i32, z: i32) -> Result<Self, CoreError> {
        let half = PREVIEW_SIDE as i32 / 2;
        let min_x = x.checked_sub(half).ok_or(CoreError::ResourceLimit)?;
        let min_z = z.checked_sub(half).ok_or(CoreError::ResourceLimit)?;
        Ok(Self {
            min_x,
            min_z,
            max_x: min_x
                .checked_add(PREVIEW_SIDE as i32 - 1)
                .ok_or(CoreError::ResourceLimit)?,
            max_z: min_z
                .checked_add(PREVIEW_SIDE as i32 - 1)
                .ok_or(CoreError::ResourceLimit)?,
        })
    }
    pub fn contains_chunks(&self, x: i32, z: i32, width: u8, depth: u8) -> bool {
        let min_x = i64::from(x) * 16;
        let min_z = i64::from(z) * 16;
        min_x >= i64::from(self.min_x)
            && min_z >= i64::from(self.min_z)
            && min_x + i64::from(width) * 16 - 1 <= i64::from(self.max_x)
            && min_z + i64::from(depth) * 16 - 1 <= i64::from(self.max_z)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_java_bounds_and_full_chunks() {
        let b = PreviewBounds::new(-1, 17).unwrap();
        assert_eq!((b.min_x, b.max_x, b.min_z, b.max_z), (-513, 510, -495, 528));
        assert_eq!(b.max_x.div_euclid(16) - b.min_x.div_euclid(16) + 1, 65);
        assert!(b.contains_chunks(-32, -30, 8, 8));
        assert!(!b.contains_chunks(-33, -30, 8, 8));
        assert!(!b.contains_chunks(24, 0, 8, 8));
        assert!(PreviewBounds::new(i32::MIN, 0).is_err());
        assert!(PreviewBounds::new(i32::MAX, 0).is_err());
    }
}
