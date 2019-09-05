use lmdb::{Database, DatabaseFlags};

use crate::protocol_data::ProtocolData;
use crate::protocol_data_store::{ProtocolDataStore, ProtocolVersion};
use crate::store::Store;
use crate::transaction_source::lmdb::LmdbEnvironment;
use crate::{error, protocol_data_store};

/// An LMDB-backed protocol data store.
///
/// Wraps [`lmdb::Database`].
#[derive(Debug, Clone)]
pub struct LmdbProtocolDataStore {
    db: Database,
}

impl LmdbProtocolDataStore {
    pub fn new(
        env: &LmdbEnvironment,
        maybe_name: Option<&str>,
        flags: DatabaseFlags,
    ) -> Result<Self, error::Error> {
        let name = maybe_name
            .map(|name| format!("{}-{}", protocol_data_store::NAME, name))
            .unwrap_or_else(|| String::from(protocol_data_store::NAME));
        let db = env.env().create_db(Some(&name), flags)?;
        Ok(LmdbProtocolDataStore { db })
    }

    pub fn open(env: &LmdbEnvironment, name: Option<&str>) -> Result<Self, error::Error> {
        let db = env.env().open_db(name)?;
        Ok(LmdbProtocolDataStore { db })
    }
}

impl Store<ProtocolVersion, ProtocolData> for LmdbProtocolDataStore {
    type Error = error::Error;

    type Handle = Database;

    fn handle(&self) -> Self::Handle {
        self.db
    }
}

impl ProtocolDataStore for LmdbProtocolDataStore {}
