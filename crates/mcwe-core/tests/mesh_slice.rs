use fastnbt::Value;
use mcwe_core::{
    anvil::ChunkCoordinate,
    cancel::{Cancellation, NeverCancel},
    chunk::decode_chunk,
    mesh::{build_mesh, ChunkRect, MeshWarningKind, Neighborhood, RenderLayer},
    CoreError,
};
use std::collections::{BTreeMap, HashMap};

fn chunk(
    coordinate: ChunkCoordinate,
    blocks: &[(u8, u8, u8, &str)],
) -> mcwe_core::chunk::DecodedChunk {
    let mut names = vec!["minecraft:air"];
    for (_, _, _, name) in blocks {
        if !names.contains(name) {
            names.push(name);
        }
    }
    let palette = names
        .iter()
        .map(|name| {
            Value::Compound(HashMap::from([(
                "Name".to_owned(),
                Value::String((*name).to_owned()),
            )]))
        })
        .collect();
    let mut words = vec![0_u64; 256];
    for &(x, y, z, name) in blocks {
        let slot = usize::from(y) * 256 + usize::from(z) * 16 + usize::from(x);
        let index = names
            .iter()
            .position(|candidate| candidate == &name)
            .unwrap();
        words[slot / 16] |= (index as u64) << ((slot % 16) * 4);
    }
    let section = Value::Compound(HashMap::from([
        ("Y".to_owned(), Value::Byte(0)),
        (
            "block_states".to_owned(),
            Value::Compound(HashMap::from([
                ("palette".to_owned(), Value::List(palette)),
                (
                    "data".to_owned(),
                    Value::LongArray(fastnbt::LongArray::new(
                        words.into_iter().map(|word| word as i64).collect(),
                    )),
                ),
            ])),
        ),
    ]));
    let bytes = fastnbt::to_bytes(&HashMap::from([
        ("xPos".to_owned(), Value::Int(coordinate.x)),
        ("zPos".to_owned(), Value::Int(coordinate.z)),
        ("sections".to_owned(), Value::List(vec![section])),
    ]))
    .unwrap();
    decode_chunk(&bytes, coordinate, &NeverCancel).unwrap()
}

fn neighborhood(chunks: Vec<mcwe_core::chunk::DecodedChunk>) -> Neighborhood {
    Neighborhood {
        chunks: chunks
            .into_iter()
            .map(|chunk| (chunk.coordinate, chunk))
            .collect::<BTreeMap<_, _>>(),
        ..Neighborhood::default()
    }
}

fn rect(min_x: i32, min_z: i32, width: u8, depth: u8) -> ChunkRect {
    ChunkRect {
        min_x,
        min_z,
        width,
        depth,
    }
}

#[test]
fn fully_occluded_material_keeps_its_block_statistics() {
    let mut blocks = Vec::new();
    for y in 0..3 {
        for z in 0..3 {
            for x in 0..3 {
                let name = if (x, y, z) == (1, 1, 1) {
                    "minecraft:dirt"
                } else {
                    "minecraft:stone"
                };
                blocks.push((x, y, z, name));
            }
        }
    }
    let mesh = build_mesh(
        &neighborhood(vec![chunk(ChunkCoordinate { x: 0, z: 0 }, &blocks)]),
        rect(0, 0, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    assert_eq!(mesh.block_count, 27);
    assert_eq!(mesh.batches.iter().map(|b| b.block_count).sum::<u32>(), 27);
    assert!(mesh
        .batches
        .iter()
        .any(|b| b.block_count == 1 && b.index_count == 0));
    assert_eq!(mesh.face_count, 6);
}

#[test]
fn one_block_has_six_faces_and_axis_aligned_buffers() {
    let c = ChunkCoordinate { x: 0, z: 0 };
    let mesh = build_mesh(
        &neighborhood(vec![chunk(c, &[(0, 0, 0, "minecraft:stone")])]),
        rect(0, 0, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    assert_eq!((mesh.block_count, mesh.face_count), (1, 6));
    assert_eq!(
        (mesh.positions.len(), mesh.normals.len(), mesh.indices.len()),
        (72, 72, 36)
    );
    assert_eq!(mesh.batches.len(), 1);
    assert_eq!(mesh.batches[0].layer, RenderLayer::Opaque);
    assert!(mesh.indices.iter().all(|index| (*index as usize) < 24));
    assert!(mesh
        .normals
        .iter()
        .all(|value| [-1.0, 0.0, 1.0].contains(value)));
    assert_eq!((mesh.origin_world_x, mesh.origin_world_z), (0, 0));
    assert_eq!(mesh.bounds.unwrap().max_y, 1.0);
}

#[test]
fn adjacent_blocks_cull_the_internal_face_and_merge_coplanar_faces() {
    let c = ChunkCoordinate { x: 0, z: 0 };
    let mesh = build_mesh(
        &neighborhood(vec![chunk(
            c,
            &[(0, 0, 0, "minecraft:stone"), (1, 0, 0, "minecraft:stone")],
        )]),
        rect(0, 0, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    assert_eq!(mesh.block_count, 2);
    assert_eq!(mesh.face_count, 6);
}

#[test]
fn halo_culls_only_target_faces_and_missing_halo_opens_them() {
    let target = ChunkCoordinate { x: 0, z: 0 };
    let halo = ChunkCoordinate { x: 1, z: 0 };
    let target_chunk = chunk(target, &[(15, 0, 0, "minecraft:stone")]);
    let closed = build_mesh(
        &neighborhood(vec![
            target_chunk.clone(),
            chunk(halo, &[(0, 0, 0, "minecraft:stone")]),
        ]),
        rect(0, 0, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    let open = build_mesh(
        &neighborhood(vec![target_chunk]),
        rect(0, 0, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    assert_eq!((closed.block_count, closed.face_count), (1, 5));
    assert_eq!((open.block_count, open.face_count), (1, 6));
}

#[test]
fn fluid_face_against_solid_matches_java_visibility_semantics() {
    let target = ChunkCoordinate { x: 0, z: 0 };
    let halo = ChunkCoordinate { x: 1, z: 0 };
    let mesh = build_mesh(
        &neighborhood(vec![
            chunk(target, &[(15, 0, 0, "minecraft:water")]),
            chunk(halo, &[(0, 0, 0, "minecraft:stone")]),
        ]),
        rect(0, 0, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    assert_eq!(mesh.face_count, 5);
}

#[test]
fn transparent_and_fallback_boundaries_match_java_visibility_semantics() {
    let target = ChunkCoordinate { x: 0, z: 0 };
    let halo = ChunkCoordinate { x: 1, z: 0 };
    for (target_name, halo_name, expected_faces) in [
        ("minecraft:glass", "minecraft:glass", 5),
        ("minecraft:glass", "minecraft:tinted_glass", 6),
        ("minecraft:stone", "example:block", 5),
    ] {
        let mesh = build_mesh(
            &neighborhood(vec![
                chunk(target, &[(15, 0, 0, target_name)]),
                chunk(halo, &[(0, 0, 0, halo_name)]),
            ]),
            rect(0, 0, 1, 1),
            &NeverCancel,
        )
        .unwrap();
        assert_eq!(
            mesh.face_count, expected_faces,
            "{target_name} / {halo_name}"
        );
    }
}

#[test]
fn partial_target_and_fallback_blocks_produce_aggregated_warnings() {
    let target = ChunkCoordinate { x: 0, z: 0 };
    let mesh = build_mesh(
        &neighborhood(vec![chunk(target, &[(0, 0, 0, "example:block")])]),
        rect(0, 0, 2, 1),
        &NeverCancel,
    )
    .unwrap();
    assert_eq!(mesh.block_count, 1);
    assert!(mesh.warnings.iter().any(|warning| {
        warning.kind == MeshWarningKind::TargetUnavailable
            && warning.coordinate == ChunkCoordinate { x: 1, z: 0 }
    }));
    assert!(mesh.warnings.iter().any(|warning| {
        warning.kind == MeshWarningKind::FallbackBlock
            && warning.block_name.as_deref() == Some("example:block")
            && warning.occurrences == 1
    }));
    assert!(mesh
        .warnings
        .iter()
        .any(|warning| warning.kind == MeshWarningKind::NeighborUnavailable));
}

#[test]
fn fallback_warnings_are_aggregated_by_block_name_across_chunks() {
    let left = ChunkCoordinate { x: 0, z: 0 };
    let right = ChunkCoordinate { x: 1, z: 0 };
    let mesh = build_mesh(
        &neighborhood(vec![
            chunk(left, &[(0, 0, 0, "example:block")]),
            chunk(right, &[(0, 0, 0, "example:block")]),
        ]),
        rect(0, 0, 2, 1),
        &NeverCancel,
    )
    .unwrap();
    let fallback = mesh
        .warnings
        .iter()
        .filter(|warning| warning.kind == MeshWarningKind::FallbackBlock)
        .collect::<Vec<_>>();
    assert_eq!(fallback.len(), 1);
    assert_eq!(fallback[0].block_name.as_deref(), Some("example:block"));
    assert_eq!(fallback[0].occurrences, 2);
}

#[test]
fn two_by_two_generates_only_target_chunks_and_all_missing_is_rejected() {
    let chunks = [
        ChunkCoordinate { x: 0, z: 0 },
        ChunkCoordinate { x: 1, z: 0 },
        ChunkCoordinate { x: 0, z: 1 },
        ChunkCoordinate { x: 1, z: 1 },
    ]
    .into_iter()
    .map(|coordinate| chunk(coordinate, &[(0, 0, 0, "minecraft:stone")]))
    .collect();
    let mesh = build_mesh(&neighborhood(chunks), rect(0, 0, 2, 2), &NeverCancel).unwrap();
    assert_eq!(mesh.block_count, 4);
    assert_eq!(mesh.bounds.unwrap().max_x, 17.0);
    assert_eq!(mesh.bounds.unwrap().max_z, 17.0);

    assert!(matches!(
        build_mesh(&Neighborhood::default(), rect(0, 0, 2, 2), &NeverCancel,),
        Err(CoreError::UnsupportedChunk)
    ));
}

#[test]
fn negative_world_chunks_still_emit_selection_local_vertices() {
    let c = ChunkCoordinate { x: -1, z: -1 };
    let mesh = build_mesh(
        &neighborhood(vec![chunk(c, &[(0, 0, 0, "minecraft:stone")])]),
        rect(-1, -1, 1, 1),
        &NeverCancel,
    )
    .unwrap();
    assert!(mesh
        .positions
        .iter()
        .all(|value| (0.0..=1.0).contains(value)));
    assert_eq!((mesh.origin_world_x, mesh.origin_world_z), (-16, -16));
}

#[test]
fn output_is_deterministic_and_invalid_rectangles_are_rejected() {
    let c = ChunkCoordinate { x: 0, z: 0 };
    let n = neighborhood(vec![chunk(c, &[(0, 0, 0, "example:block")])]);
    let first = build_mesh(&n, rect(0, 0, 1, 1), &NeverCancel).unwrap();
    let second = build_mesh(&n, rect(0, 0, 1, 1), &NeverCancel).unwrap();
    assert_eq!(first, second);
    for invalid in [rect(0, 0, 0, 1), rect(0, 0, 1, 0), rect(0, 0, 9, 1)] {
        assert!(matches!(
            build_mesh(&n, invalid, &NeverCancel),
            Err(CoreError::InvalidChunk)
        ));
    }

    assert!(rect(-32, -30, 8, 8).validate_for_preview(-1, 17).is_ok());
    assert!(rect(23, 25, 8, 8).validate_for_preview(-1, 17).is_ok());
    assert!(rect(-33, -30, 1, 1).validate_for_preview(-1, 17).is_err());
    assert!(rect(24, 25, 8, 8).validate_for_preview(-1, 17).is_err());

    let one = rect(0, 0, 1, 1).neighborhood_coordinates().unwrap();
    let two = rect(0, 0, 2, 2).neighborhood_coordinates().unwrap();
    assert_eq!((one.len(), two.len()), (9, 16));
    assert!(two.contains(&ChunkCoordinate { x: -1, z: -1 }));
    assert!(two.contains(&ChunkCoordinate { x: 2, z: 2 }));
    assert!(!two.contains(&ChunkCoordinate { x: -2, z: 0 }));
    assert!(!two.contains(&ChunkCoordinate { x: 3, z: 0 }));
}

struct AlwaysCancelled;

impl Cancellation for AlwaysCancelled {
    fn is_cancelled(&self) -> bool {
        true
    }
}

#[test]
fn cancellation_stops_before_mesh_generation() {
    assert!(matches!(
        build_mesh(&Neighborhood::default(), rect(0, 0, 1, 1), &AlwaysCancelled),
        Err(CoreError::Cancelled)
    ));
}
#[test]
fn java_presets_generate_larger_target_bounds() {
    for size in [1, 3, 5, 8] {
        let mut chunks = Vec::new();
        for z in 0..size {
            for x in 0..size {
                chunks.push(chunk(
                    ChunkCoordinate {
                        x: i32::from(x),
                        z: i32::from(z),
                    },
                    &[(0, 0, 0, "minecraft:stone")],
                ));
            }
        }
        let n = neighborhood(chunks);
        let mesh = build_mesh(&n, rect(0, 0, size, size), &NeverCancel).unwrap();
        assert_eq!(mesh.block_count, u32::from(size) * u32::from(size));
        assert_eq!(
            rect(0, 0, size, size)
                .neighborhood_coordinates()
                .unwrap()
                .len(),
            usize::from(size + 2).pow(2)
        );
    }
}
