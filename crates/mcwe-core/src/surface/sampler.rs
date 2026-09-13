use crate::{cancel::Cancellation, chunk::DecodedChunk, surface::block_color, CoreError};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct SurfaceCell {
    pub color: u32,
    pub height: i32,
}

pub fn sample_chunk(
    chunk: &DecodedChunk,
    cancel: &dyn Cancellation,
) -> Result<Vec<SurfaceCell>, CoreError> {
    let mut result = vec![SurfaceCell::default(); 256];
    let Some((&top, _)) = chunk.sections.last_key_value() else {
        return Ok(result);
    };
    let Some((&bottom, _)) = chunk.sections.first_key_value() else {
        return Ok(result);
    };
    for z in 0..16_u8 {
        for x in 0..16_u8 {
            cancel.check()?;
            'height: for y in (i32::from(bottom) * 16..=i32::from(top) * 16 + 15).rev() {
                if let Some(state) = chunk.block(x, y, z) {
                    if !state.is_air() {
                        result[usize::from(z) * 16 + usize::from(x)] = SurfaceCell {
                            color: block_color(&state.name),
                            height: y,
                        };
                        break 'height;
                    }
                }
            }
        }
    }
    Ok(result)
}
