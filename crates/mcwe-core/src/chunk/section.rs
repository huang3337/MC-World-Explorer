use super::BlockState;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkSection {
    pub y: i8,
    palette: Vec<BlockState>,
    indices: Vec<u16>,
}

impl ChunkSection {
    /// Conservative retained storage accounting, including collection overhead.
    pub fn storage_budget_bytes(&self) -> usize {
        self.indices.capacity() * 2
            + self.palette.capacity() * std::mem::size_of::<BlockState>()
            + self
                .palette
                .iter()
                .map(|state| {
                    state.name.capacity()
                        + state
                            .properties
                            .iter()
                            .map(|(k, v)| k.capacity() + v.capacity() + 128)
                            .sum::<usize>()
                })
                .sum::<usize>()
            + 256
    }
    pub(crate) fn new(y: i8, palette: Vec<BlockState>, indices: Vec<u16>) -> Self {
        Self {
            y,
            palette,
            indices,
        }
    }
    pub fn block(&self, x: u8, y: u8, z: u8) -> Option<&BlockState> {
        if x >= 16 || y >= 16 || z >= 16 {
            return None;
        }
        let slot = usize::from(y) * 256 + usize::from(z) * 16 + usize::from(x);
        self.palette.get(usize::from(*self.indices.get(slot)?))
    }
}
