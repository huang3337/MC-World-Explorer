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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MapLoadAnchorSource {
    PlayerExit,
    WorldSpawn,
    OriginFallback,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreviewCenter {
    pub position: WorldPosition,
    pub source: PreviewCenterSource,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MapLoadAnchor {
    pub position: WorldPosition,
    pub source: MapLoadAnchorSource,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldInfo {
    pub data_version: Option<i32>,
    pub name: String,
    pub world_spawn: Option<WorldPosition>,
    pub player_respawn: Option<WorldPosition>,
    pub preview_center: PreviewCenter,
    pub player_exit: Option<WorldPosition>,
    pub map_load_anchor: MapLoadAnchor,
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
    let mut info = parse(&input, cancel)?;
    resolve_player_data_anchor(source, &mut info, cancel)?;
    Ok(info)
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
    let player_exit = root.data.player.as_ref().and_then(player_exit_position);
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
    let map_load_anchor = map_load_anchor(player_exit.clone(), world_spawn.clone());
    Ok(WorldInfo {
        data_version: root.data.data_version,
        name,
        world_spawn,
        player_respawn,
        preview_center,
        player_exit,
        map_load_anchor,
    })
}

fn resolve_player_data_anchor(
    source: &WorldSource,
    info: &mut WorldInfo,
    cancel: &dyn Cancellation,
) -> Result<(), CoreError> {
    cancel.check()?;
    let names = match source.list_regular_file_names("playerdata", 4096) {
        Ok(names) => names,
        Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
        Err(_) => return Ok(()),
    };
    let players = names
        .into_iter()
        .filter_map(|name| name.into_string().ok())
        .filter(|name| is_player_data_name(name))
        .collect::<Vec<_>>();
    if players.len() > 1 {
        info.player_exit = None;
        info.map_load_anchor = map_load_anchor(None, info.world_spawn.clone());
        return Ok(());
    }
    let Some(name) = players.first() else {
        return Ok(());
    };
    let player_exit = match read_player_data_position(source, name, cancel) {
        Ok(position) => position,
        Err(CoreError::Cancelled) => return Err(CoreError::Cancelled),
        Err(_) => None,
    };
    info.player_exit = player_exit.clone();
    info.map_load_anchor = map_load_anchor(player_exit, info.world_spawn.clone());
    Ok(())
}

fn read_player_data_position(
    source: &WorldSource,
    name: &str,
    cancel: &dyn Cancellation,
) -> Result<Option<WorldPosition>, CoreError> {
    cancel.check()?;
    let file = source.open_file(format!("playerdata/{name}"))?;
    let input = read_limited(file, LEVEL_INPUT_BYTES, cancel)?;
    let player: Value = decode_nbt(&input, NBT_OUTPUT_BYTES, cancel)?;
    Ok(player_exit_position(&player))
}

fn player_exit_position(player: &Value) -> Option<WorldPosition> {
    let Value::Compound(player) = player else {
        return None;
    };
    if !is_overworld(player.get("Dimension")) {
        return None;
    }
    let Value::List(position) = player.get("Pos")? else {
        return None;
    };
    if position.len() < 3 {
        return None;
    }
    Some(WorldPosition {
        x: coordinate(&position[0])?,
        y: coordinate(&position[1])?,
        z: coordinate(&position[2])?,
    })
}

fn coordinate(value: &Value) -> Option<i32> {
    let value = match value {
        Value::Byte(value) => f64::from(*value),
        Value::Short(value) => f64::from(*value),
        Value::Int(value) => f64::from(*value),
        Value::Long(value) => *value as f64,
        Value::Float(value) => f64::from(*value),
        Value::Double(value) => *value,
        _ => return None,
    };
    if !value.is_finite() || value < f64::from(i32::MIN) || value >= f64::from(i32::MAX) + 1.0 {
        return None;
    }
    Some(value.floor() as i32)
}

fn is_player_data_name(name: &str) -> bool {
    let Some(uuid) = name.strip_suffix(".dat") else {
        return false;
    };
    uuid.len() == 36
        && uuid.as_bytes().iter().enumerate().all(|(index, value)| {
            if [8, 13, 18, 23].contains(&index) {
                *value == b'-'
            } else {
                value.is_ascii_hexdigit()
            }
        })
}

fn map_load_anchor(
    player_exit: Option<WorldPosition>,
    world_spawn: Option<WorldPosition>,
) -> MapLoadAnchor {
    if let Some(position) = player_exit {
        MapLoadAnchor {
            position,
            source: MapLoadAnchorSource::PlayerExit,
        }
    } else if let Some(position) = world_spawn {
        MapLoadAnchor {
            position,
            source: MapLoadAnchorSource::WorldSpawn,
        }
    } else {
        MapLoadAnchor {
            position: WorldPosition { x: 0, y: 0, z: 0 },
            source: MapLoadAnchorSource::OriginFallback,
        }
    }
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
