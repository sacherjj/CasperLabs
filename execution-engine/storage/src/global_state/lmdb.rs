use common::bytesrepr::{FromBytes, ToBytes};
use common::key::Key;
use common::value::Value;
use error;
use global_state::StateReader;
use history::trie::operations::create_hashed_empty_trie;
use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};
use history::trie_store::operations::{read, ReadResult};
use history::trie_store::{Transaction, TransactionSource, TrieStore};
use lmdb;
use shared::newtypes::Blake2bHash;
use std::ops::Deref;
use std::sync::Arc;

/// Represents a "view" of global state at a particular root hash.
pub struct LmdbGlobalState {
    pub(super) environment: Arc<LmdbEnvironment>,
    pub(super) store: Arc<LmdbTrieStore>,
    pub(super) root_hash: Blake2bHash,
}

impl LmdbGlobalState {
    /// Creates an empty state from an existing environment and store.
    pub fn empty(
        environment: Arc<LmdbEnvironment>,
        store: Arc<LmdbTrieStore>,
    ) -> Result<Self, error::Error> {
        let root_hash: Blake2bHash = {
            let (root_hash, root) = create_hashed_empty_trie::<Key, Value>()?;
            let mut txn = environment.create_read_write_txn()?;
            store.put(&mut txn, &root_hash, &root)?;
            txn.commit()?;
            root_hash
        };
        Ok(LmdbGlobalState::new(environment, store, root_hash))
    }

    /// Creates a state from an existing environment, store, and root_hash.
    /// Intended to be used for testing.
    pub(crate) fn new(
        environment: Arc<LmdbEnvironment>,
        store: Arc<LmdbTrieStore>,
        root_hash: Blake2bHash,
    ) -> Self {
        LmdbGlobalState {
            environment,
            store,
            root_hash,
        }
    }
}

impl<K, V> StateReader<K, V> for LmdbGlobalState
where
    K: ToBytes + FromBytes + Eq + std::fmt::Debug,
    V: ToBytes + FromBytes,
{
    type Error = error::Error;

    fn read(&self, key: &K) -> Result<Option<V>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let ret = match read::<K, V, lmdb::RoTransaction, LmdbTrieStore, Self::Error>(
            &txn,
            self.store.deref(),
            &self.root_hash,
            key,
        )? {
            ReadResult::Found(value) => Some(value),
            ReadResult::NotFound => None,
            ReadResult::RootNotFound => panic!("LmdbGlobalState has invalid root"),
        };
        txn.commit()?;
        Ok(ret)
    }
}
