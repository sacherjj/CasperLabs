use std::collections::HashMap;
use std::ops::Deref;
use std::sync::Arc;

use contract_ffi::key::Key;
use contract_ffi::value::{ProtocolVersion, Value};
use engine_shared::newtypes::{Blake2bHash, CorrelationId};
use engine_shared::transform::Transform;

use crate::error::{self, in_memory};
use crate::global_state::StateReader;
use crate::global_state::{commit, CommitResult, StateProvider};
use crate::protocol_data::ProtocolData;
use crate::protocol_data_store::in_memory::InMemoryProtocolDataStore;
use crate::store::Store;
use crate::transaction_source::in_memory::{InMemoryEnvironment, InMemoryReadTransaction};
use crate::transaction_source::{Transaction, TransactionSource};
use crate::trie::operations::create_hashed_empty_trie;
use crate::trie::Trie;
use crate::trie_store::in_memory::InMemoryTrieStore;
use crate::trie_store::operations;
use crate::trie_store::operations::{read, ReadResult, WriteResult};

pub struct InMemoryGlobalState {
    pub environment: Arc<InMemoryEnvironment>,
    pub trie_store: Arc<InMemoryTrieStore>,
    pub protocol_data_store: Arc<InMemoryProtocolDataStore>,
    pub empty_root_hash: Blake2bHash,
}

/// Represents a "view" of global state at a particular root hash.
pub struct InMemoryGlobalStateView {
    pub environment: Arc<InMemoryEnvironment>,
    pub store: Arc<InMemoryTrieStore>,
    pub root_hash: Blake2bHash,
}

impl InMemoryGlobalState {
    /// Creates an empty state.
    pub fn empty() -> Result<Self, error::Error> {
        let environment = Arc::new(InMemoryEnvironment::new());
        let trie_store = Arc::new(InMemoryTrieStore::new(&environment, None));
        let protocol_data_store = Arc::new(InMemoryProtocolDataStore::new(&environment, None));
        let root_hash: Blake2bHash = {
            let (root_hash, root) = create_hashed_empty_trie::<Key, Value>()?;
            let mut txn = environment.create_read_write_txn()?;
            trie_store.put(&mut txn, &root_hash, &root)?;
            txn.commit()?;
            root_hash
        };
        Ok(InMemoryGlobalState::new(
            environment,
            trie_store,
            protocol_data_store,
            root_hash,
        ))
    }

    /// Creates a state from an existing environment, trie_Store, and root_hash.
    /// Intended to be used for testing.
    pub(crate) fn new(
        environment: Arc<InMemoryEnvironment>,
        trie_store: Arc<InMemoryTrieStore>,
        protocol_data_store: Arc<InMemoryProtocolDataStore>,
        empty_root_hash: Blake2bHash,
    ) -> Self {
        InMemoryGlobalState {
            environment,
            trie_store,
            protocol_data_store,
            empty_root_hash,
        }
    }

    /// Creates a state from a given set of [`Key`](contract_ffi::key::key),
    /// [`Value`](contract_ffi::value::Value) pairs
    pub fn from_pairs(
        correlation_id: CorrelationId,
        pairs: &[(Key, Value)],
    ) -> Result<(Self, Blake2bHash), error::Error> {
        let state = InMemoryGlobalState::empty()?;
        let mut current_root = state.empty_root_hash;
        {
            let mut txn = state.environment.create_read_write_txn()?;
            for (key, value) in pairs {
                let key = key.normalize();
                match operations::write::<_, _, _, InMemoryTrieStore, in_memory::Error>(
                    correlation_id,
                    &mut txn,
                    &state.trie_store,
                    &current_root,
                    &key,
                    value,
                )? {
                    WriteResult::Written(root_hash) => {
                        current_root = root_hash;
                    }
                    WriteResult::AlreadyExists => (),
                    WriteResult::RootNotFound => panic!("InMemoryGlobalState has invalid root"),
                }
            }
            txn.commit()?;
        }
        Ok((state, current_root))
    }
}

impl StateReader<Key, Value> for InMemoryGlobalStateView {
    type Error = error::Error;

    fn read(&self, correlation_id: CorrelationId, key: &Key) -> Result<Option<Value>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let ret = match read::<Key, Value, InMemoryReadTransaction, InMemoryTrieStore, Self::Error>(
            correlation_id,
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

impl StateProvider for InMemoryGlobalState {
    type Error = error::Error;

    type Reader = InMemoryGlobalStateView;

    fn checkout(&self, prestate_hash: Blake2bHash) -> Result<Option<Self::Reader>, Self::Error> {
        let txn = self.environment.create_read_txn()?;
        let maybe_root: Option<Trie<Key, Value>> = self.trie_store.get(&txn, &prestate_hash)?;
        let maybe_state = maybe_root.map(|_| InMemoryGlobalStateView {
            environment: Arc::clone(&self.environment),
            store: Arc::clone(&self.trie_store),
            root_hash: prestate_hash,
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
        let commit_result = commit::<InMemoryEnvironment, InMemoryTrieStore, _, Self::Error>(
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
    use engine_shared::test_utils;

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

    fn create_test_state() -> (InMemoryGlobalState, Blake2bHash) {
        InMemoryGlobalState::from_pairs(
            CorrelationId::new(),
            &TEST_PAIRS
                .iter()
                .cloned()
                .map(|TestPair { key, value }| (key, value))
                .collect::<Vec<(Key, Value)>>(),
        )
        .unwrap()
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

        let effects: HashMap<Key, Transform> = test_pairs_updated
            .iter()
            .cloned()
            .map(|TestPair { key, value }| (key, Transform::Write(value)))
            .collect();

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

    #[test]
    fn initial_state_has_the_expected_hash() {
        let correlation_id = CorrelationId::new();
        let expected_bytes = vec![
            56u8, 92, 10, 147, 138, 170, 197, 149, 117, 180, 207, 20, 211, 210, 180, 162, 221, 248,
            223, 184, 82, 235, 248, 63, 88, 63, 199, 231, 80, 50, 193, 34,
        ];
        let init_state = test_utils::mocked_account([48u8; 32]);
        let (_, root_hash) = InMemoryGlobalState::from_pairs(correlation_id, &init_state).unwrap();
        assert_eq!(expected_bytes, root_hash.to_vec())
    }
}
