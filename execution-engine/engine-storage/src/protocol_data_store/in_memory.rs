use crate::error::in_memory::Error;
use crate::protocol_data::ProtocolData;
use crate::protocol_data_store::{self, ProtocolDataStore, ProtocolVersion};
use crate::store::Store;
use crate::transaction_source::in_memory::InMemoryEnvironment;

/// An in-memory protocol data store
pub struct InMemoryProtocolDataStore {
    maybe_name: Option<String>,
}

impl InMemoryProtocolDataStore {
    pub fn new(_env: &InMemoryEnvironment, maybe_name: Option<&str>) -> Self {
        let name = maybe_name
            .map(|name| format!("{}-{}", protocol_data_store::NAME, name))
            .unwrap_or_else(|| String::from(protocol_data_store::NAME));
        InMemoryProtocolDataStore {
            maybe_name: Some(name),
        }
    }
}

impl Store<ProtocolVersion, ProtocolData> for InMemoryProtocolDataStore {
    type Error = Error;
    type Handle = Option<String>;

    fn handle(&self) -> Self::Handle {
        self.maybe_name.to_owned()
    }
}

impl ProtocolDataStore for InMemoryProtocolDataStore {}
