use common::key::Key;
use common::value::Value;
use global_state::StateReader;
use history::trie::operations::create_hashed_empty_trie;
use history::trie_store::in_memory::{
    self, InMemoryEnvironment, InMemoryReadTransaction, InMemoryTrieStore,
};
use history::trie_store::operations::{read, ReadResult};
use history::trie_store::{Transaction, TransactionSource, TrieStore};
use shared::newtypes::Blake2bHash;
use std::ops::Deref;
use std::sync::Arc;

/// Represents a "view" of global state at a particular root hash.
pub struct InMemoryGlobalState {
    pub environment: Arc<InMemoryEnvironment>,
    pub store: Arc<InMemoryTrieStore>,
    pub root_hash: Blake2bHash,
}

impl InMemoryGlobalState {
    /// Creates an empty state from an existing environment and store.
    pub fn empty(
        environment: Arc<InMemoryEnvironment>,
        store: Arc<InMemoryTrieStore>,
    ) -> Result<Self, in_memory::Error> {
        let root_hash: Blake2bHash = {
            let (root_hash, root) = create_hashed_empty_trie::<Key, Value>()?;
            let mut txn = environment.create_read_write_txn()?;
            store.put(&mut txn, &root_hash, &root)?;
            txn.commit()?;
            root_hash
        };
        Ok(InMemoryGlobalState::new(environment, store, root_hash))
    }

    /// Creates a state from an existing environment, store, and root_hash.
    /// Intended to be used for testing.
    pub(crate) fn new(
        environment: Arc<InMemoryEnvironment>,
        store: Arc<InMemoryTrieStore>,
        root_hash: Blake2bHash,
    ) -> Self {
        InMemoryGlobalState {
            environment,
            store,
            root_hash,
        }
    }
}

impl StateReader<Key, Value> for InMemoryGlobalState {
    type Error = in_memory::Error;

    fn read(&self, key: &Key) -> Result<Option<Value>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let ret = match read::<Key, Value, InMemoryReadTransaction, InMemoryTrieStore, Self::Error>(
            &txn,
            self.store.deref(),
            &self.root_hash,
            key,
        )? {
            ReadResult::Found(value) => Some(value),
            ReadResult::NotFound => None,
            ReadResult::RootNotFound => panic!("InMemoryGlobalState has invalid root"),
        };
        txn.commit()?;
        Ok(ret)
    }
}
