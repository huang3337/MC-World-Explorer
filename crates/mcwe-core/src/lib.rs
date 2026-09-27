//! MC World Explorer 的框架无关核心边界。

#![forbid(unsafe_code)]

pub mod anvil;
pub mod area;
pub mod cancel;
pub mod chunk;
mod error;
pub mod limits;
pub mod map;
pub mod mesh;
mod nbt;
pub mod surface;
pub mod world;
mod world_source;

pub use error::CoreError;
pub use world_source::{SourceFileState, WorldFile, WorldSource};
