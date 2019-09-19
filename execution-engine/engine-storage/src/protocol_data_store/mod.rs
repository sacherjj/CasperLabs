//! A store for persisting [`ProtocolData`] values at their protocol versions.
use contract_ffi::value::ProtocolVersion;

pub mod in_memory;
pub mod lmdb;
#[cfg(test)]
mod tests;

use crate::protocol_data::ProtocolData;
use crate::store::Store;

const NAME: &str = "PROTOCOL_DATA_STORE";

/// An entity which persists [`ProtocolData`] values at their protocol versions.
pub trait ProtocolDataStore: Store<ProtocolVersion, ProtocolData> {}
