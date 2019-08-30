use std::ops::RangeInclusive;

use lmdb::DatabaseFlags;
use proptest::collection::vec;
use proptest::prelude::proptest;
use tempfile::tempdir;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::newtypes::Blake2bHash;

use super::TestData;
use crate::transaction_source::{Transaction, TransactionSource};
use crate::trie::gens::trie_arb;
use crate::trie::Trie;
use crate::trie_store::TrieStore;
use crate::TEST_MAP_SIZE;

const DEFAULT_MIN_LENGTH: usize = 1;

const DEFAULT_MAX_LENGTH: usize = 4;

fn get_range() -> RangeInclusive<usize> {
    let start = option_env!("CL_TRIE_STORE_TEST_VECTOR_MIN_LENGTH")
        .and_then(|s| str::parse::<usize>(s).ok())
        .unwrap_or(DEFAULT_MIN_LENGTH);
    let end = option_env!("CL_TRIE_STORE_TEST_VECTOR_MAX_LENGTH")
        .and_then(|s| str::parse::<usize>(s).ok())
        .unwrap_or(DEFAULT_MAX_LENGTH);
    RangeInclusive::new(start, end)
}

fn roundtrip<'a, K, V, S, X, E>(
    store: &S,
    transaction_source: &'a X,
    items: &[TestData<K, V>],
) -> Result<Vec<Option<Trie<K, V>>>, E>
where
    K: ToBytes,
    V: ToBytes,
    S: TrieStore<K, V>,
    X: TransactionSource<'a, Handle = S::Handle>,
    S::Error: From<X::Error>,
    E: From<S::Error> + From<X::Error>,
{
    let mut txn: X::ReadWriteTransaction = transaction_source.create_read_write_txn()?;
    super::put_many::<_, _, _, _, E>(&mut txn, store, items)?;
    let keys: Vec<&Blake2bHash> = items.iter().map(|TestData(k, _)| k).collect();
    let result = super::get_many::<_, _, _, _, E>(&txn, store, &keys);
    txn.commit()?;
    result
}

fn roundtrip_succeeds<'a, S, X, E>(
    store: &S,
    transaction_source: &'a X,
    inputs: Vec<Trie<Key, Value>>,
) -> bool
where
    S: TrieStore<Key, Value>,
    X: TransactionSource<'a, Handle = S::Handle>,
    S::Error: From<X::Error>,
    E: From<S::Error> + From<X::Error> + std::fmt::Debug,
{
    let outputs: Vec<Trie<Key, Value>> = {
        let input_tuples: Vec<TestData<Key, Value>> = inputs
            .iter()
            .map(|trie| TestData(Blake2bHash::new(&trie.to_bytes().unwrap()), trie.to_owned()))
            .collect();
        roundtrip::<_, _, _, _, E>(store, transaction_source, &input_tuples)
            .expect("roundtrip failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Key, Value>>>>()
            .expect("one of the outputs was empty")
    };

    outputs == inputs
}

fn in_memory_roundtrip_succeeds(inputs: Vec<Trie<Key, Value>>) -> bool {
    use crate::error::in_memory;
    use crate::transaction_source::in_memory::InMemoryEnvironment;
    use crate::trie_store::in_memory::InMemoryTrieStore;

    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);

    roundtrip_succeeds::<_, _, in_memory::Error>(&store, &env, inputs)
}

fn lmdb_roundtrip_succeeds(inputs: Vec<Trie<Key, Value>>) -> bool {
    use crate::error;
    use crate::transaction_source::lmdb::LmdbEnvironment;
    use crate::trie_store::lmdb::LmdbTrieStore;

    let tmp_dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

    let ret = roundtrip_succeeds::<_, _, error::Error>(&store, &env, inputs);
    tmp_dir.close().unwrap();
    ret
}

proptest! {
    #[test]
    fn prop_in_memory_roundtrip_succeeds(v in vec(trie_arb(), get_range())) {
        assert!(in_memory_roundtrip_succeeds(v))
    }

    #[test]
    fn prop_lmdb_roundtrip_succeeds(v in vec(trie_arb(), get_range())) {
        assert!(lmdb_roundtrip_succeeds(v))
    }
}
