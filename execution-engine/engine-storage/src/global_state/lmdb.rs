use std::collections::HashMap;
use std::ops::Deref;
use std::sync::Arc;

use lmdb;

use contract_ffi::key::Key;
use contract_ffi::value::{ProtocolVersion, Value};
use engine_shared::newtypes::{Blake2bHash, CorrelationId};
use engine_shared::transform::Transform;

use crate::error;
use crate::global_state::StateReader;
use crate::global_state::{commit, CommitResult, StateProvider};
use crate::protocol_data::ProtocolData;
use crate::protocol_data_store::lmdb::LmdbProtocolDataStore;
use crate::store::Store;
use crate::transaction_source::lmdb::LmdbEnvironment;
use crate::transaction_source::{Transaction, TransactionSource};
use crate::trie::operations::create_hashed_empty_trie;
use crate::trie::Trie;
use crate::trie_store::lmdb::LmdbTrieStore;
use crate::trie_store::operations::{read, ReadResult};

pub struct LmdbGlobalState {
    pub environment: Arc<LmdbEnvironment>,
    pub trie_store: Arc<LmdbTrieStore>,
    pub protocol_data_store: Arc<LmdbProtocolDataStore>,
    pub empty_root_hash: Blake2bHash,
}

/// Represents a "view" of global state at a particular root hash.
pub struct LmdbGlobalStateView {
    pub environment: Arc<LmdbEnvironment>,
    pub store: Arc<LmdbTrieStore>,
    pub root_hash: Blake2bHash,
}

impl LmdbGlobalState {
    /// Creates an empty state from an existing environment and trie_store.
    pub fn empty(
        environment: Arc<LmdbEnvironment>,
        trie_store: Arc<LmdbTrieStore>,
        protocol_data_store: Arc<LmdbProtocolDataStore>,
    ) -> Result<Self, error::Error> {
        let root_hash: Blake2bHash = {
            let (root_hash, root) = create_hashed_empty_trie::<Key, Value>()?;
            let mut txn = environment.create_read_write_txn()?;
            trie_store.put(&mut txn, &root_hash, &root)?;
            txn.commit()?;
            root_hash
        };
        Ok(LmdbGlobalState::new(
            environment,
            trie_store,
            protocol_data_store,
            root_hash,
        ))
    }

    /// Creates a state from an existing environment, store, and root_hash.
    /// Intended to be used for testing.
    pub(crate) fn new(
        environment: Arc<LmdbEnvironment>,
        trie_store: Arc<LmdbTrieStore>,
        protocol_data_store: Arc<LmdbProtocolDataStore>,
        empty_root_hash: Blake2bHash,
    ) -> Self {
        LmdbGlobalState {
            environment,
            trie_store,
            protocol_data_store,
            empty_root_hash,
        }
    }
}

impl StateReader<Key, Value> for LmdbGlobalStateView {
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

impl StateProvider for LmdbGlobalState {
    type Error = error::Error;

    type Reader = LmdbGlobalStateView;

    fn checkout(&self, state_hash: Blake2bHash) -> Result<Option<Self::Reader>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let maybe_root: Option<Trie<Key, Value>> = self.trie_store.get(&txn, &state_hash)?;
        let maybe_state = maybe_root.map(|_| LmdbGlobalStateView {
            environment: Arc::clone(&self.environment),
            store: Arc::clone(&self.trie_store),
            root_hash: state_hash,
        });
        txn.commit()?;
        Ok(maybe_state)
    }

    fn commit(
        &self,
        correlation_id: CorrelationId,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error> {
        let commit_result = commit::<LmdbEnvironment, LmdbTrieStore, _, Self::Error>(
            &self.environment,
            &self.trie_store,
            correlation_id,
            prestate_hash,
            effects,
        )?;
        Ok(commit_result)
    }

    fn put_protocol_data(
        &self,
        protocol_version: ProtocolVersion,
        protocol_data: &ProtocolData,
    ) -> Result<(), Self::Error> {
        let mut txn = self.environment.create_read_write_txn()?;
        self.protocol_data_store
            .put(&mut txn, &protocol_version, protocol_data)?;
        txn.commit().map_err(Into::into)
    }

    fn get_protocol_data(
        &self,
        protocol_version: ProtocolVersion,
    ) -> Result<Option<ProtocolData>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let result = self.protocol_data_store.get(&txn, &protocol_version)?;
        txn.commit()?;
        Ok(result)
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

    fn create_test_state() -> (LmdbGlobalState, Blake2bHash) {
        let correlation_id = CorrelationId::new();
        let _temp_dir = tempdir().unwrap();
        let environment = Arc::new(
            LmdbEnvironment::new(&_temp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap(),
        );
        let trie_store =
            Arc::new(LmdbTrieStore::new(&environment, None, DatabaseFlags::empty()).unwrap());
        let protocol_data_store = Arc::new(
            LmdbProtocolDataStore::new(&environment, None, DatabaseFlags::empty()).unwrap(),
        );
        let ret = LmdbGlobalState::empty(environment, trie_store, protocol_data_store).unwrap();
        let mut current_root = ret.empty_root_hash;
        {
            let mut txn = ret.environment.create_read_write_txn().unwrap();

            for TestPair { key, value } in &TEST_PAIRS {
                match write::<_, _, _, LmdbTrieStore, error::Error>(
                    correlation_id,
                    &mut txn,
                    &ret.trie_store,
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

            txn.commit().unwrap();
        }
        (ret, current_root)
    }

    #[test]
    fn reads_from_a_checkout_return_expected_values() {
        let correlation_id = CorrelationId::new();
        let (state, root_hash) = create_test_state();
        let checkout = state.checkout(root_hash).unwrap().unwrap();
        for TestPair { key, value } in TEST_PAIRS.iter().cloned() {
            assert_eq!(Some(value), checkout.read(correlation_id, &key).unwrap());
        }
    }

    #[test]
    fn checkout_fails_if_unknown_hash_is_given() {
        let (state, _) = create_test_state();
        let fake_hash: Blake2bHash = [1u8; 32].into();
        let result = state.checkout(fake_hash).unwrap();
        assert!(result.is_none());
    }

    #[test]
    fn commit_updates_state() {
        let correlation_id = CorrelationId::new();
        let test_pairs_updated = create_test_pairs_updated();

        let (state, root_hash) = create_test_state();

        let effects: HashMap<Key, Transform> = {
            let mut tmp = HashMap::new();
            for TestPair { key, value } in &test_pairs_updated {
                tmp.insert(*key, Transform::Write(value.to_owned()));
            }
            tmp
        };

        let updated_hash = match state.commit(correlation_id, root_hash, effects).unwrap() {
            CommitResult::Success { state_root, .. } => state_root,
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

        let (state, root_hash) = create_test_state();

        let effects: HashMap<Key, Transform> = {
            let mut tmp = HashMap::new();
            for TestPair { key, value } in &test_pairs_updated {
                tmp.insert(*key, Transform::Write(value.to_owned()));
            }
            tmp
        };

        let updated_hash = match state.commit(correlation_id, root_hash, effects).unwrap() {
            CommitResult::Success { state_root, .. } => state_root,
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
