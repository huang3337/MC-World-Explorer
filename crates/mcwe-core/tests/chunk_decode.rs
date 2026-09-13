use fastnbt::Value;
use mcwe_core::{
    anvil::ChunkCoordinate,
    cancel::{Cancellation, NeverCancel},
    chunk::decode_chunk,
    limits::{MAX_PALETTE, MAX_SECTIONS},
    CoreError,
};
use std::collections::{BTreeMap, HashMap};

fn palette_entry(name: &str) -> Value {
    Value::Compound(HashMap::from([(
        "Name".to_owned(),
        Value::String(name.to_owned()),
    )]))
}

fn palette_entry_with_properties(name: &str, properties: HashMap<String, Value>) -> Value {
    Value::Compound(HashMap::from([
        ("Name".to_owned(), Value::String(name.to_owned())),
        ("Properties".to_owned(), Value::Compound(properties)),
    ]))
}

fn modern_section(y: i8, palette: Vec<Value>, data: Option<Vec<i64>>) -> Value {
    let mut states = HashMap::from([("palette".to_owned(), Value::List(palette))]);
    if let Some(data) = data {
        states.insert(
            "data".to_owned(),
            Value::LongArray(fastnbt::LongArray::new(data)),
        );
    }
    Value::Compound(HashMap::from([
        ("Y".to_owned(), Value::Byte(y)),
        ("block_states".to_owned(), Value::Compound(states)),
    ]))
}

fn modern_bytes(x: Option<i32>, z: Option<i32>, sections: Vec<Value>) -> Vec<u8> {
    let mut root = HashMap::from([("sections".to_owned(), Value::List(sections))]);
    if let Some(x) = x {
        root.insert("xPos".to_owned(), Value::Int(x));
    }
    if let Some(z) = z {
        root.insert("zPos".to_owned(), Value::Int(z));
    }
    fastnbt::to_bytes(&root).unwrap()
}

fn legacy_bytes(x: i32, z: i32, sections: Vec<Value>) -> Vec<u8> {
    fastnbt::to_bytes(&HashMap::from([(
        "Level".to_owned(),
        Value::Compound(HashMap::from([
            ("xPos".to_owned(), Value::Int(x)),
            ("zPos".to_owned(), Value::Int(z)),
            ("Sections".to_owned(), Value::List(sections)),
        ])),
    )]))
    .unwrap()
}

fn legacy_section(y: i8, palette: Option<Vec<Value>>, data: Option<Vec<i64>>) -> Value {
    let mut section = HashMap::from([("Y".to_owned(), Value::Byte(y))]);
    if let Some(palette) = palette {
        section.insert("Palette".to_owned(), Value::List(palette));
    }
    if let Some(data) = data {
        section.insert(
            "BlockStates".to_owned(),
            Value::LongArray(fastnbt::LongArray::new(data)),
        );
    }
    Value::Compound(section)
}

fn palette(count: usize) -> Vec<Value> {
    (0..count)
        .map(|index| palette_entry(&format!("test:block_{index}")))
        .collect()
}

fn packed(indices: &[(usize, usize)], palette_len: usize, padded: bool) -> Vec<i64> {
    let bits = usize::max(
        4,
        usize::BITS as usize - (palette_len - 1).leading_zeros() as usize,
    );
    if padded {
        let per_long = 64 / bits;
        let mut words = vec![0_u64; 4096_usize.div_ceil(per_long)];
        for &(slot, value) in indices {
            words[slot / per_long] |= (value as u64) << (slot % per_long * bits);
        }
        words.into_iter().map(|word| word as i64).collect()
    } else {
        let mut words = vec![0_u64; (4096 * bits).div_ceil(64)];
        for &(slot, value) in indices {
            let bit = slot * bits;
            let word = bit / 64;
            let shift = bit % 64;
            words[word] |= (value as u64) << shift;
            if shift + bits > 64 {
                words[word + 1] |= (value as u64) >> (64 - shift);
            }
        }
        words.into_iter().map(|word| word as i64).collect()
    }
}

fn modern_root(extra: Option<(&str, Value)>) -> Vec<u8> {
    let mut root = HashMap::from([
        ("xPos".to_owned(), Value::Int(0)),
        ("zPos".to_owned(), Value::Int(0)),
        (
            "sections".to_owned(),
            Value::List(vec![modern_section(
                0,
                vec![palette_entry("minecraft:air")],
                None,
            )]),
        ),
    ]);
    if let Some((name, value)) = extra {
        root.insert(name.to_owned(), value);
    }
    fastnbt::to_bytes(&root).unwrap()
}

#[test]
fn rejects_excessive_nesting_before_chunk_deserialization() {
    let mut nested = Value::Byte(1);
    for _ in 0..65 {
        nested = Value::Compound(HashMap::from([("nested".to_owned(), nested)]));
    }
    assert!(matches!(
        decode_chunk(
            &modern_root(Some(("ignored", nested))),
            ChunkCoordinate { x: 0, z: 0 },
            &NeverCancel,
        ),
        Err(CoreError::ResourceLimit)
    ));
}

#[test]
fn decodes_modern_and_legacy_layouts_with_properties_and_negative_y() {
    let properties = HashMap::from([
        ("waterlogged".to_owned(), Value::String("false".to_owned())),
        ("axis".to_owned(), Value::String("x".to_owned())),
    ]);
    let entry = palette_entry_with_properties("minecraft:oak_log", properties);
    let expected = BTreeMap::from([
        ("axis".to_owned(), "x".to_owned()),
        ("waterlogged".to_owned(), "false".to_owned()),
    ]);
    for bytes in [
        modern_bytes(
            Some(-2),
            Some(3),
            vec![modern_section(-1, vec![entry.clone()], None)],
        ),
        legacy_bytes(
            -2,
            3,
            vec![legacy_section(-1, Some(vec![entry.clone()]), None)],
        ),
    ] {
        let chunk = decode_chunk(&bytes, ChunkCoordinate { x: -2, z: 3 }, &NeverCancel).unwrap();
        let block = chunk.block(0, -16, 0).unwrap();
        assert_eq!(block.name, "minecraft:oak_log");
        assert_eq!(block.properties, expected);
        assert!(chunk.block(16, -16, 0).is_none());
    }
}

#[test]
fn decodes_padded_and_compact_long_layouts_across_word_boundaries() {
    let entries = palette(17);
    let expected = [(0, 1), (11, 2), (12, 16), (4095, 7)];
    for padded_layout in [true, false] {
        let bytes = modern_bytes(
            Some(0),
            Some(0),
            vec![modern_section(
                0,
                entries.clone(),
                Some(packed(&expected, entries.len(), padded_layout)),
            )],
        );
        let chunk = decode_chunk(&bytes, ChunkCoordinate { x: 0, z: 0 }, &NeverCancel).unwrap();
        for (slot, value) in expected {
            let x = (slot % 16) as u8;
            let z = ((slot / 16) % 16) as u8;
            let y = (slot / 256) as i32;
            assert_eq!(
                chunk.block(x, y, z).unwrap().name,
                format!("test:block_{value}")
            );
        }
    }
}

#[test]
fn rejects_short_extra_and_out_of_palette_packed_data() {
    let entries = palette(2);
    let valid = packed(&[], entries.len(), true);
    for data in [
        valid[..valid.len() - 1].to_vec(),
        {
            let mut data = valid.clone();
            data.push(0);
            data
        },
        packed(&[(0, 2)], entries.len(), true),
    ] {
        assert!(matches!(
            decode_chunk(
                &modern_bytes(
                    Some(0),
                    Some(0),
                    vec![modern_section(0, entries.clone(), Some(data))],
                ),
                ChunkCoordinate { x: 0, z: 0 },
                &NeverCancel,
            ),
            Err(CoreError::InvalidChunk)
        ));
    }
}

#[test]
fn rejects_ambiguous_layout_coordinates_and_duplicate_sections() {
    let section = modern_section(0, vec![palette_entry("minecraft:air")], None);
    for bytes in [
        modern_bytes(None, Some(0), vec![section.clone()]),
        modern_bytes(Some(1), Some(0), vec![section.clone()]),
        modern_bytes(Some(0), Some(0), vec![section.clone(), section.clone()]),
        {
            let mut root = fastnbt::from_bytes::<HashMap<String, Value>>(&modern_bytes(
                Some(0),
                Some(0),
                vec![section.clone()],
            ))
            .unwrap();
            root.insert(
                "Level".to_owned(),
                Value::Compound(HashMap::from([(
                    "Sections".to_owned(),
                    Value::List(vec![legacy_section(
                        0,
                        Some(vec![palette_entry("minecraft:air")]),
                        None,
                    )]),
                )])),
            );
            fastnbt::to_bytes(&root).unwrap()
        },
    ] {
        assert!(matches!(
            decode_chunk(&bytes, ChunkCoordinate { x: 0, z: 0 }, &NeverCancel,),
            Err(CoreError::InvalidChunk)
        ));
    }
}

#[test]
fn enforces_section_palette_and_property_type_limits() {
    let too_many_sections = (0..=MAX_SECTIONS)
        .map(|index| Value::Compound(HashMap::from([("Y".to_owned(), Value::Byte(index as i8))])))
        .collect();
    assert!(matches!(
        decode_chunk(
            &modern_bytes(Some(0), Some(0), too_many_sections),
            ChunkCoordinate { x: 0, z: 0 },
            &NeverCancel,
        ),
        Err(CoreError::ResourceLimit)
    ));

    for (entries, expected_resource_limit) in
        [(Vec::new(), false), (palette(MAX_PALETTE + 1), true)]
    {
        let error = decode_chunk(
            &modern_bytes(Some(0), Some(0), vec![modern_section(0, entries, None)]),
            ChunkCoordinate { x: 0, z: 0 },
            &NeverCancel,
        )
        .unwrap_err();
        assert_eq!(
            matches!(error, CoreError::ResourceLimit),
            expected_resource_limit
        );
    }

    let wrong_property = palette_entry_with_properties(
        "minecraft:stone",
        HashMap::from([("age".to_owned(), Value::Int(1))]),
    );
    assert!(matches!(
        decode_chunk(
            &modern_bytes(
                Some(0),
                Some(0),
                vec![modern_section(0, vec![wrong_property], None)],
            ),
            ChunkCoordinate { x: 0, z: 0 },
            &NeverCancel,
        ),
        Err(CoreError::InvalidNbt)
    ));
}

#[test]
fn accepts_more_than_sixty_four_distinct_sections_within_the_y_domain() {
    let sections = (-64_i16..68)
        .map(|y| modern_section(y as i8, palette(1), None))
        .collect();
    let decoded = decode_chunk(
        &modern_bytes(Some(0), Some(0), sections),
        ChunkCoordinate { x: 0, z: 0 },
        &NeverCancel,
    )
    .unwrap();
    assert_eq!(decoded.sections.len(), 132);
}

#[test]
fn rejects_unsupported_blocks_data_and_sections_without_a_palette() {
    let blocks_data = Value::Compound(HashMap::from([
        ("Y".to_owned(), Value::Byte(0)),
        (
            "Blocks".to_owned(),
            Value::ByteArray(fastnbt::ByteArray::new(vec![0; 4096])),
        ),
        (
            "Data".to_owned(),
            Value::ByteArray(fastnbt::ByteArray::new(vec![0; 2048])),
        ),
    ]));
    for bytes in [
        legacy_bytes(0, 0, vec![blocks_data]),
        modern_bytes(
            Some(0),
            Some(0),
            vec![Value::Compound(HashMap::from([(
                "Y".to_owned(),
                Value::Byte(0),
            )]))],
        ),
    ] {
        assert!(matches!(
            decode_chunk(&bytes, ChunkCoordinate { x: 0, z: 0 }, &NeverCancel,),
            Err(CoreError::UnsupportedChunk)
        ));
    }
}

struct AlwaysCancelled;

impl Cancellation for AlwaysCancelled {
    fn is_cancelled(&self) -> bool {
        true
    }
}

#[test]
fn cancellation_stops_before_chunk_deserialization() {
    assert!(matches!(
        decode_chunk(
            &modern_root(None),
            ChunkCoordinate { x: 0, z: 0 },
            &AlwaysCancelled,
        ),
        Err(CoreError::Cancelled)
    ));
}
