#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ChunkCoordinate {
    pub x: i32,
    pub z: i32,
}

impl ChunkCoordinate {
    pub fn region(self) -> (i32, i32) {
        (self.x.div_euclid(32), self.z.div_euclid(32))
    }
    pub fn local(self) -> (u8, u8) {
        (self.x.rem_euclid(32) as u8, self.z.rem_euclid(32) as u8)
    }
    pub fn slot(self) -> usize {
        let (x, z) = self.local();
        usize::from(z) * 32 + usize::from(x)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn negative_coordinates_use_floor_division() {
        let cases = [
            ((-1, -1), (-1, -1), (31, 31)),
            ((-32, -32), (-1, -1), (0, 0)),
            ((-33, 32), (-2, 1), (31, 0)),
        ];
        for ((x, z), region, local) in cases {
            let c = ChunkCoordinate { x, z };
            assert_eq!(c.region(), region);
            assert_eq!(c.local(), local);
        }
    }
}
