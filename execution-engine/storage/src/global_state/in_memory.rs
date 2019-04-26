use common::key::Key;
use common::value::Value;
use history::trie::operations::create_hashed_empty_trie;
use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};
use history::trie_store::{Transaction, TransactionSource, TrieStore};
use shared::newtypes::Blake2bHash;
use std::sync::Arc;

/// Represents a "view" of global state at a particular root hash.
pub struct InMemoryGlobalState {
    environment: Arc<InMemoryEnvironment>,
    store: Arc<InMemoryTrieStore>,
    root_hash: Blake2bHash,
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
        Ok(InMemoryGlobalState {
            environment,
            store,
            root_hash,
        })
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
