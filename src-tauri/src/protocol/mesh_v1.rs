use super::header::{encode, f32_bytes, u32_bytes, Section};
use mcwe_core::{
    limits::{
        MAX_MESH_BATCHES, MAX_MESH_INDICES, MAX_MESH_VERTICES, MAX_TEXT_BYTES, MAX_TOTAL_TEXT_BYTES,
    },
    mesh::{ChunkFailure, ChunkRect, MeshData, MeshWarningKind},
    CoreError,
};

pub fn encode_mesh_v1(
    mesh: &MeshData,
    rect: ChunkRect,
    session_id: u64,
    task_id: u64,
) -> Result<Vec<u8>, CoreError> {
    rect.validate()?;
    if !mesh.positions.len().is_multiple_of(3)
        || mesh.normals.len() != mesh.positions.len()
        || !mesh.indices.len().is_multiple_of(3)
    {
        return Err(CoreError::InvalidChunk);
    }
    let vertices = mesh.positions.len() / 3;
    if vertices > MAX_MESH_VERTICES
        || mesh.indices.len() > MAX_MESH_INDICES
        || mesh.batches.len() > MAX_MESH_BATCHES
        || mesh.indices.iter().any(|i| *i as usize >= vertices)
        || mesh.face_count as usize != mesh.indices.len() / 6
        || !mesh.indices.len().is_multiple_of(6)
        || !valid_normals(&mesh.normals)
        || !valid_bounds(mesh)
    {
        return Err(CoreError::InvalidChunk);
    }
    let expected_origin_x = rect.min_x.checked_mul(16).ok_or(CoreError::ResourceLimit)?;
    let expected_origin_z = rect.min_z.checked_mul(16).ok_or(CoreError::ResourceLimit)?;
    if mesh.origin_world_x != expected_origin_x || mesh.origin_world_z != expected_origin_z {
        return Err(CoreError::InvalidChunk);
    }
    let table_len = mesh
        .batches
        .len()
        .checked_mul(8)
        .ok_or(CoreError::ResourceLimit)?;
    let mut table = Vec::with_capacity(table_len);
    let mut next_index = 0_u32;
    let mut batch_blocks = 0_u32;
    let mut batch_faces = 0_u32;
    for b in &mesh.batches {
        let end = b
            .first_index
            .checked_add(b.index_count)
            .ok_or(CoreError::InvalidChunk)?;
        if b.index_count % 6 != 0
            || b.first_index != next_index
            || usize::try_from(end).map_err(|_| CoreError::InvalidChunk)? > mesh.indices.len()
            || !b.alpha.is_finite()
            || !(0.0..=1.0).contains(&b.alpha)
            || b.rgb > 0x00ff_ffff
            || b.face_count != b.index_count / 6
        {
            return Err(CoreError::InvalidChunk);
        }
        next_index = end;
        batch_blocks = batch_blocks
            .checked_add(b.block_count)
            .ok_or(CoreError::ResourceLimit)?;
        batch_faces = batch_faces
            .checked_add(b.face_count)
            .ok_or(CoreError::ResourceLimit)?;
        table.extend_from_slice(&[
            b.layer as u32,
            b.rgb,
            b.alpha.to_bits(),
            b.first_index,
            b.index_count,
            b.block_count,
            b.face_count,
            0,
        ]);
    }
    if next_index as usize != mesh.indices.len()
        || batch_blocks != mesh.block_count
        || batch_faces != mesh.face_count
    {
        return Err(CoreError::InvalidChunk);
    }
    let bounds = bounds_json(mesh)?;
    let warnings = warnings_json(mesh)?;
    let metadata = format!(
        r#"{{"sessionId":{session_id},"taskId":{task_id},"chunkRect":{{"minX":{},"minZ":{},"width":{},"depth":{}}},"originWorldX":{},"originWorldZ":{},"bounds":{bounds},"blockCount":{},"faceCount":{},"vertexCount":{},"indexCount":{},"batchCount":{},"warnings":{warnings}}}"#,
        rect.min_x,
        rect.min_z,
        rect.width,
        rect.depth,
        mesh.origin_world_x,
        mesh.origin_world_z,
        mesh.block_count,
        mesh.face_count,
        vertices,
        mesh.indices.len(),
        mesh.batches.len()
    );
    encode(
        2,
        vec![
            Section {
                id: 10,
                scalar: 6,
                count: u32::try_from(mesh.positions.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: f32_bytes(mesh.positions.iter().copied())?,
            },
            Section {
                id: 11,
                scalar: 6,
                count: u32::try_from(mesh.normals.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: f32_bytes(mesh.normals.iter().copied())?,
            },
            Section {
                id: 12,
                scalar: 4,
                count: u32::try_from(mesh.indices.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: u32_bytes(mesh.indices.iter().copied())?,
            },
            Section {
                id: 13,
                scalar: 4,
                count: u32::try_from(table.len()).map_err(|_| CoreError::ResourceLimit)?,
                bytes: u32_bytes(table.into_iter())?,
            },
        ],
        &metadata,
    )
}

fn valid_normals(normals: &[f32]) -> bool {
    normals.as_chunks::<3>().0.iter().all(|normal| {
        normal
            .iter()
            .all(|value| matches!(*value, -1.0 | 0.0 | 1.0))
            && normal.iter().filter(|value| **value != 0.0).count() == 1
    })
}

fn valid_bounds(mesh: &MeshData) -> bool {
    match (mesh.bounds, mesh.positions.is_empty()) {
        (None, true) => true,
        (None, false) | (Some(_), true) => false,
        (Some(bounds), false) => {
            let values = [
                bounds.min_x,
                bounds.min_y,
                bounds.min_z,
                bounds.max_x,
                bounds.max_y,
                bounds.max_z,
            ];
            values.iter().all(|value| value.is_finite())
                && bounds.min_x <= bounds.max_x
                && bounds.min_y <= bounds.max_y
                && bounds.min_z <= bounds.max_z
                && mesh.positions.as_chunks::<3>().0.iter().all(|position| {
                    position[0].is_finite()
                        && position[1].is_finite()
                        && position[2].is_finite()
                        && (bounds.min_x..=bounds.max_x).contains(&position[0])
                        && (bounds.min_y..=bounds.max_y).contains(&position[1])
                        && (bounds.min_z..=bounds.max_z).contains(&position[2])
                })
        }
    }
}

fn bounds_json(mesh: &MeshData) -> Result<String, CoreError> {
    let Some(bounds) = mesh.bounds else {
        return Ok(String::from("null"));
    };
    if !valid_bounds(mesh) {
        return Err(CoreError::InvalidChunk);
    }
    Ok(format!(
        r#"{{"minX":{},"minY":{},"minZ":{},"maxX":{},"maxY":{},"maxZ":{}}}"#,
        bounds.min_x, bounds.min_y, bounds.min_z, bounds.max_x, bounds.max_y, bounds.max_z
    ))
}

fn warnings_json(mesh: &MeshData) -> Result<String, CoreError> {
    let mut out = String::from("[");
    for (index, warning) in mesh.warnings.iter().enumerate() {
        if warning.occurrences == 0 {
            return Err(CoreError::InvalidChunk);
        }
        match warning.kind {
            MeshWarningKind::TargetUnavailable | MeshWarningKind::NeighborUnavailable
                if warning.failure.is_none() || warning.block_name.is_some() =>
            {
                return Err(CoreError::InvalidChunk);
            }
            MeshWarningKind::FallbackBlock
                if warning.failure.is_some() || warning.block_name.is_none() =>
            {
                return Err(CoreError::InvalidChunk);
            }
            _ => {}
        }
        let kind = match warning.kind {
            MeshWarningKind::TargetUnavailable => "targetUnavailable",
            MeshWarningKind::NeighborUnavailable => "neighborUnavailable",
            MeshWarningKind::FallbackBlock => "fallbackBlock",
        };
        let failure = match warning.failure {
            Some(ChunkFailure::Missing) => r#""missing""#,
            Some(ChunkFailure::Unsupported) => r#""unsupported""#,
            Some(ChunkFailure::Corrupt) => r#""corrupt""#,
            None => "null",
        };
        let block_name = match warning.block_name.as_deref() {
            Some(value) if value.len() <= MAX_TEXT_BYTES => {
                format!(r#""{}""#, escape_json(value))
            }
            Some(_) => return Err(CoreError::ResourceLimit),
            None => String::from("null"),
        };
        if index != 0 {
            out.push(',');
        }
        out.push_str(&format!(
            r#"{{"kind":"{kind}","chunkX":{},"chunkZ":{},"failure":{failure},"blockName":{block_name},"occurrences":{}}}"#,
            warning.coordinate.x, warning.coordinate.z, warning.occurrences
        ));
        if out.len() > MAX_TOTAL_TEXT_BYTES {
            return Err(CoreError::ResourceLimit);
        }
    }
    out.push(']');
    Ok(out)
}

fn escape_json(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{08}' => out.push_str("\\b"),
            '\u{0c}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            value if value < '\u{20}' => out.push_str(&format!("\\u{:04x}", value as u32)),
            value => out.push(value),
        }
    }
    out
}
