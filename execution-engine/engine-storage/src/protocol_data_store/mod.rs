//! A store for persisting [`ProtocolData`] values at their protocol versions.

pub mod in_memory;
pub mod lmdb;
#[cfg(test)]
mod tests;

use crate::protocol_data::ProtocolData;
use crate::store::Store;

const NAME: &str = "PROTOCOL_DATA_STORE";

/// TODO: update to reflect semantic versioning
pub type ProtocolVersion = u64;

/// An entity which persists [`ProtocolData`] values at their protocol versions.
pub trait ProtocolDataStore: Store<ProtocolVersion, ProtocolData> {}
