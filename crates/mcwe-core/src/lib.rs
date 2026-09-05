//! MC World Explorer 的框架无关核心边界。

#![forbid(unsafe_code)]

mod error;
mod world_source;

pub use error::CoreError;
pub use world_source::{WorldFile, WorldSource};
