use std::collections::HashMap;
use std::ops::Deref;
use std::sync::Arc;

use lmdb;

use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::newtypes::{Blake2bHash, CorrelationId};
use engine_shared::transform::Transform;

use crate::error;
use crate::global_state::StateReader;
use crate::global_state::{commit, CommitResult, History};
use crate::store::Store;
use crate::transaction_source::lmdb::LmdbEnvironment;
use crate::transaction_source::{Transaction, TransactionSource};
use crate::trie::operations::create_hashed_empty_trie;
use crate::trie::Trie;
use crate::trie_store::lmdb::LmdbTrieStore;
use crate::trie_store::operations::{read, ReadResult};

/// Represents a "view" of global state at a particular root hash.
pub struct LmdbGlobalState {
    pub(super) environment: Arc<LmdbEnvironment>,
    pub(super) store: Arc<LmdbTrieStore>,
    pub(super) root_hash: Blake2bHash,
    pub(super) empty_root_hash: Blake2bHash,
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
        Ok(LmdbGlobalState::new(
            environment,
            store,
            root_hash,
            root_hash,
        ))
    }

    /// Creates a state from an existing environment, store, and root_hash.
    /// Intended to be used for testing.
    pub(crate) fn new(
        environment: Arc<LmdbEnvironment>,
        store: Arc<LmdbTrieStore>,
        root_hash: Blake2bHash,
        empty_root_hash: Blake2bHash,
    ) -> Self {
        LmdbGlobalState {
            environment,
            store,
            root_hash,
            empty_root_hash,
        }
    }
}

impl StateReader<Key, Value> for LmdbGlobalState {
    type Error = error::Error;

    fn read(&self, correlation_id: CorrelationId, key: &Key) -> Result<Option<Value>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let ret = match read::<Key, Value, lmdb::RoTransaction, LmdbTrieStore, Self::Error>(
            correlation_id,
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

impl History for LmdbGlobalState {
    type Error = error::Error;

    type Reader = Self;

    fn checkout(&self, prestate_hash: Blake2bHash) -> Result<Option<Self::Reader>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let maybe_root: Option<Trie<Key, Value>> = self.store.get(&txn, &prestate_hash)?;
        let maybe_state = maybe_root.map(|_| LmdbGlobalState {
            environment: Arc::clone(&self.environment),
            store: Arc::clone(&self.store),
            root_hash: prestate_hash,
            empty_root_hash: self.empty_root_hash,
        });
        txn.commit()?;
        Ok(maybe_state)
    }

    fn commit(
        &mut self,
        correlation_id: CorrelationId,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error> {
        let commit_result = commit::<LmdbEnvironment, LmdbTrieStore, _, Self::Error>(
            &self.environment,
            &self.store,
            correlation_id,
            prestate_hash,
            effects,
        )?;
        if let CommitResult::Success(root_hash) = commit_result {
            self.root_hash = root_hash;
        };
        Ok(commit_result)
    }

    fn current_root(&self) -> Blake2bHash {
        self.root_hash
    }

    fn empty_root(&self) -> Blake2bHash {
        self.empty_root_hash
    }
}

#[cfg(test)]
mod tests {
    use lmdb::DatabaseFlags;
    use tempfile::tempdir;

    use crate::trie_store::operations::{write, WriteResult};
    use crate::TEST_MAP_SIZE;

    use super::*;

    #[derive(Debug, Clone)]
    struct TestPair {
        key: Key,
        value: Value,
    }

    const TEST_PAIRS: [TestPair; 2] = [
        TestPair {
            key: Key::Account([1u8; 32]),
            value: Value::Int32(1),
        },
        TestPair {
            key: Key::Account([2u8; 32]),
            value: Value::Int32(2),
        },
    ];

    fn create_test_pairs_updated() -> [TestPair; 3] {
        [
            TestPair {
                key: Key::Account([1u8; 32]),
                value: Value::String("one".to_string()),
            },
            TestPair {
                key: Key::Account([2u8; 32]),
                value: Value::String("two".to_string()),
            },
            TestPair {
                key: Key::Account([3u8; 32]),
                value: Value::Int32(3),
            },
        ]
    }

    fn create_test_state() -> LmdbGlobalState {
        let correlation_id = CorrelationId::new();
        let _temp_dir = tempdir().unwrap();
        let environment = Arc::new(
            LmdbEnvironment::new(&_temp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap(),
        );
        let store =
            Arc::new(LmdbTrieStore::new(&environment, None, DatabaseFlags::empty()).unwrap());
        let mut ret = LmdbGlobalState::empty(environment, store).unwrap();
        {
            let mut txn = ret.environment.create_read_write_txn().unwrap();
            let mut current_root = ret.root_hash;

            for TestPair { key, value } in &TEST_PAIRS {
                match write::<_, _, _, LmdbTrieStore, error::Error>(
                    correlation_id,
                    &mut txn,
                    &ret.store,
                    &current_root,
                    key,
                    value,
                )
                .unwrap()
                {
                    WriteResult::Written(root_hash) => {
                        current_root = root_hash;
                    }
                    WriteResult::AlreadyExists => (),
                    WriteResult::RootNotFound => panic!("LmdbGlobalState has invalid root"),
                }
            }

            ret.root_hash = current_root;
            txn.commit().unwrap();
        }
        ret
    }

    #[test]
    fn reads_from_a_checkout_return_expected_values() {
        let correlation_id = CorrelationId::new();
        let state = create_test_state();
        let checkout = state.checkout(state.root_hash).unwrap().unwrap();
        for TestPair { key, value } in TEST_PAIRS.iter().cloned() {
            assert_eq!(Some(value), checkout.read(correlation_id, &key).unwrap());
        }
    }

    #[test]
    fn checkout_fails_if_unknown_hash_is_given() {
        let state = create_test_state();
        let fake_hash: Blake2bHash = [1u8; 32].into();
        let result = state.checkout(fake_hash).unwrap();
        assert!(result.is_none());
    }

    #[test]
    fn commit_updates_state() {
        let correlation_id = CorrelationId::new();
        let test_pairs_updated = create_test_pairs_updated();

        let mut state = create_test_state();
        let root_hash = state.root_hash;

        let effects: HashMap<Key, Transform> = {
            let mut tmp = HashMap::new();
            for TestPair { key, value } in &test_pairs_updated {
                tmp.insert(*key, Transform::Write(value.to_owned()));
            }
            tmp
        };

        let updated_hash = match state.commit(correlation_id, root_hash, effects).unwrap() {
            CommitResult::Success(hash) => hash,
            _ => panic!("commit failed"),
        };

        let updated_checkout = state.checkout(updated_hash).unwrap().unwrap();

        for TestPair { key, value } in test_pairs_updated.iter().cloned() {
            assert_eq!(
                Some(value),
                updated_checkout.read(correlation_id, &key).unwrap()
            );
        }
    }

    #[test]
    fn commit_updates_state_and_original_state_stays_intact() {
        let correlation_id = CorrelationId::new();
        let test_pairs_updated = create_test_pairs_updated();

        let mut state = create_test_state();
        let root_hash = state.root_hash;

        let effects: HashMap<Key, Transform> = {
            let mut tmp = HashMap::new();
            for TestPair { key, value } in &test_pairs_updated {
                tmp.insert(*key, Transform::Write(value.to_owned()));
            }
            tmp
        };

        let updated_hash = match state.commit(correlation_id, root_hash, effects).unwrap() {
            CommitResult::Success(hash) => hash,
            _ => panic!("commit failed"),
        };

        let updated_checkout = state.checkout(updated_hash).unwrap().unwrap();
        for TestPair { key, value } in test_pairs_updated.iter().cloned() {
            assert_eq!(
                Some(value),
                updated_checkout.read(correlation_id, &key).unwrap()
            );
        }

        let original_checkout = state.checkout(root_hash).unwrap().unwrap();
        for TestPair { key, value } in TEST_PAIRS.iter().cloned() {
            assert_eq!(
                Some(value),
                original_checkout.read(correlation_id, &key).unwrap()
            );
        }
        assert_eq!(
            None,
            original_checkout
                .read(correlation_id, &test_pairs_updated[2].key)
                .unwrap()
        );
    }
}
