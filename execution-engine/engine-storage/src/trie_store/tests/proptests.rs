use std::ops::RangeInclusive;

use lmdb::DatabaseFlags;
use proptest::collection::vec;
use proptest::prelude::proptest;
use tempfile::tempdir;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::newtypes::Blake2bHash;

use crate::store::tests as store_tests;
use crate::trie::gens::trie_arb;
use crate::trie::Trie;
use crate::TEST_MAP_SIZE;
use std::collections::BTreeMap;

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

fn in_memory_roundtrip_succeeds(inputs: Vec<Trie<Key, Value>>) -> bool {
    use crate::transaction_source::in_memory::InMemoryEnvironment;
    use crate::trie_store::in_memory::InMemoryTrieStore;

    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env, None);

    let inputs: BTreeMap<Blake2bHash, Trie<Key, Value>> = inputs
        .into_iter()
        .map(|trie| (Blake2bHash::new(&trie.to_bytes().unwrap()), trie))
        .collect();

    store_tests::roundtrip_succeeds(&env, &store, inputs).unwrap()
}

fn lmdb_roundtrip_succeeds(inputs: Vec<Trie<Key, Value>>) -> bool {
    use crate::transaction_source::lmdb::LmdbEnvironment;
    use crate::trie_store::lmdb::LmdbTrieStore;

    let tmp_dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

    let inputs: BTreeMap<Blake2bHash, Trie<Key, Value>> = inputs
        .into_iter()
        .map(|trie| (Blake2bHash::new(&trie.to_bytes().unwrap()), trie))
        .collect();

    let ret = store_tests::roundtrip_succeeds(&env, &store, inputs).unwrap();
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
