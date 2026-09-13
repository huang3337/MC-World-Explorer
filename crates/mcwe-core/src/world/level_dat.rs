use crate::{
    cancel::Cancellation,
    limits::{LEVEL_INPUT_BYTES, MAX_TEXT_BYTES, NBT_OUTPUT_BYTES},
    nbt::{decode_nbt, read_limited},
    CoreError, WorldSource,
};
use fastnbt::Value;
use serde::Deserialize;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldPosition {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PreviewCenterSource {
    PlayerRespawn,
    WorldSpawn,
    OriginFallback,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreviewCenter {
    pub position: WorldPosition,
    pub source: PreviewCenterSource,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldInfo {
    pub data_version: Option<i32>,
    pub name: String,
    pub world_spawn: Option<WorldPosition>,
    pub player_respawn: Option<WorldPosition>,
    pub preview_center: PreviewCenter,
}

#[derive(Deserialize)]
struct Root {
    #[serde(rename = "Data")]
    data: Data,
}

#[derive(Deserialize)]
struct Data {
    #[serde(rename = "DataVersion")]
    data_version: Option<i32>,
    #[serde(rename = "LevelName")]
    level_name: Option<String>,
    #[serde(rename = "SpawnX")]
    spawn_x: Option<Value>,
    #[serde(rename = "SpawnY")]
    spawn_y: Option<Value>,
    #[serde(rename = "SpawnZ")]
    spawn_z: Option<Value>,
    #[serde(rename = "Player")]
    player: Option<Value>,
}

pub fn read_world_info(
    source: &WorldSource,
    cancel: &dyn Cancellation,
) -> Result<WorldInfo, CoreError> {
    let file = source.open_file("level.dat")?;
    let input = read_limited(file, LEVEL_INPUT_BYTES, cancel)?;
    parse(&input, cancel)
}

fn parse(input: &[u8], cancel: &dyn Cancellation) -> Result<WorldInfo, CoreError> {
    let root: Root = decode_nbt(input, NBT_OUTPUT_BYTES, cancel)?;
    let name = root.data.level_name.unwrap_or_default();
    if name.len() > MAX_TEXT_BYTES {
        return Err(CoreError::ResourceLimit);
    }
    let world_spawn = position(
        root.data.spawn_x.as_ref(),
        root.data.spawn_y.as_ref(),
        root.data.spawn_z.as_ref(),
    );
    let player_respawn = root.data.player.as_ref().and_then(|player| {
        let Value::Compound(player) = player else {
            return None;
        };
        if !is_overworld(player.get("SpawnDimension")) {
            return None;
        }
        position(
            player.get("SpawnX"),
            player.get("SpawnY"),
            player.get("SpawnZ"),
        )
    });
    let preview_center = if let Some(value) = player_respawn.clone() {
        PreviewCenter {
            position: value,
            source: PreviewCenterSource::PlayerRespawn,
        }
    } else if let Some(value) = world_spawn.clone() {
        PreviewCenter {
            position: value,
            source: PreviewCenterSource::WorldSpawn,
        }
    } else {
        PreviewCenter {
            position: WorldPosition { x: 0, y: 0, z: 0 },
            source: PreviewCenterSource::OriginFallback,
        }
    };
    Ok(WorldInfo {
        data_version: root.data.data_version,
        name,
        world_spawn,
        player_respawn,
        preview_center,
    })
}

fn position(x: Option<&Value>, y: Option<&Value>, z: Option<&Value>) -> Option<WorldPosition> {
    Some(WorldPosition {
        x: number(x?)?,
        y: number(y?)?,
        z: number(z?)?,
    })
}

fn number(value: &Value) -> Option<i32> {
    match value {
        Value::Byte(value) => Some(i32::from(*value)),
        Value::Short(value) => Some(i32::from(*value)),
        Value::Int(value) => Some(*value),
        Value::Long(value) => i32::try_from(*value).ok(),
        _ => None,
    }
}

fn is_overworld(dimension: Option<&Value>) -> bool {
    match dimension {
        None => true,
        Some(Value::String(value)) => value == "minecraft:overworld",
        Some(value) => number(value) == Some(0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cancel::NeverCancel;
    use flate2::{
        write::{GzEncoder, ZlibEncoder},
        Compression,
    };
    use serde::Serialize;
    use std::io::Write;

    #[derive(Serialize)]
    struct TestRoot {
        #[serde(rename = "Data")]
        data: TestData,
    }
    #[derive(Serialize)]
    struct TestData {
        #[serde(rename = "DataVersion")]
        data_version: i32,
        #[serde(rename = "LevelName")]
        level_name: String,
        #[serde(rename = "SpawnX")]
        spawn_x: i32,
        #[serde(rename = "SpawnY")]
        spawn_y: i32,
        #[serde(rename = "SpawnZ")]
        spawn_z: i32,
    }
    fn raw() -> Vec<u8> {
        fastnbt::to_bytes(&TestRoot {
            data: TestData {
                data_version: 3955,
                level_name: "测试".into(),
                spawn_x: -17,
                spawn_y: 64,
                spawn_z: 31,
            },
        })
        .unwrap()
    }
    #[test]
    fn accepts_raw_gzip_and_zlib() {
        let raw = raw();
        let mut variants = vec![raw.clone()];
        let mut gz = GzEncoder::new(Vec::new(), Compression::default());
        gz.write_all(&raw).unwrap();
        variants.push(gz.finish().unwrap());
        let mut z = ZlibEncoder::new(Vec::new(), Compression::default());
        z.write_all(&raw).unwrap();
        variants.push(z.finish().unwrap());
        for bytes in variants {
            let info = parse(&bytes, &NeverCancel).unwrap();
            assert_eq!(info.preview_center.position.x, -17);
            assert_eq!(info.preview_center.source, PreviewCenterSource::WorldSpawn);
            assert_eq!(info.name, "测试");
        }
    }
    #[test]
    fn rejects_missing_spawn_and_malformed_data() {
        assert!(matches!(
            parse(&[1, 2, 3], &NeverCancel),
            Err(CoreError::InvalidNbt)
        ));
    }
}
