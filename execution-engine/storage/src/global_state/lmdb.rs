use common::bytesrepr::ToBytes;
use common::key::Key;
use common::value::Value;
use error;
use history::trie::Trie;
use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};
use history::trie_store::{Transaction, TransactionSource, TrieStore};
use shared::newtypes::Blake2bHash;
use std::sync::Arc;

/// Represents a "view" of global state at a particular root hash.
pub struct LmdbGlobalState {
    environment: Arc<LmdbEnvironment>,
    store: Arc<LmdbTrieStore>,
    root_hash: Blake2bHash,
}

impl LmdbGlobalState {
    /// Creates an empty state from an existing environment and store.
    pub fn empty(
        environment: Arc<LmdbEnvironment>,
        store: Arc<LmdbTrieStore>,
    ) -> Result<Self, error::Error> {
        let root_hash: Blake2bHash = {
            let root: Trie<Key, Value> = Trie::Node {
                pointer_block: Default::default(),
            };
            let root_bytes: Vec<u8> = root.to_bytes()?;
            let root_hash = Blake2bHash::new(&root_bytes);
            let mut txn = environment.create_read_write_txn()?;
            store.put(&mut txn, &root_hash, &root).unwrap();
            txn.commit()?;
            root_hash
        };
        Ok(LmdbGlobalState {
            environment,
            store,
            root_hash,
        })
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
