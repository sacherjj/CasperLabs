use std::ops::RangeInclusive;

use proptest::array;
use proptest::collection::vec;
use proptest::prelude::{any, proptest, Strategy};

use super::*;

const DEFAULT_MIN_LENGTH: usize = 0;

const DEFAULT_MAX_LENGTH: usize = 100;

fn get_range() -> RangeInclusive<usize> {
    let start = option_env!("CL_TRIE_TEST_VECTOR_MIN_LENGTH")
        .and_then(|s| str::parse::<usize>(s).ok())
        .unwrap_or(DEFAULT_MIN_LENGTH);
    let end = option_env!("CL_TRIE_TEST_VECTOR_MAX_LENGTH")
        .and_then(|s| str::parse::<usize>(s).ok())
        .unwrap_or(DEFAULT_MAX_LENGTH);
    RangeInclusive::new(start, end)
}

fn write_pairs<'a, R, S, E>(
    correlation_id: CorrelationId,
    environment: &'a R,
    store: &S,
    root_hash: &Blake2bHash,
    pairs: &[(TestKey, TestValue)],
) -> Result<Vec<Blake2bHash>, E>
where
    R: TransactionSource<'a, Handle = S::Handle>,
    S: TrieStore<TestKey, TestValue>,
    S::Error: From<R::Error>,
    E: From<R::Error> + From<S::Error> + From<contract_ffi::bytesrepr::Error>,
{
    let mut results = Vec::new();
    if pairs.is_empty() {
        return Ok(results);
    }
    let mut root_hash = root_hash.to_owned();
    let mut txn = environment.create_read_write_txn()?;

    for (key, value) in pairs.iter() {
        match write::<_, _, _, _, E>(correlation_id, &mut txn, store, &root_hash, key, value)? {
            WriteResult::Written(hash) => {
                root_hash = hash;
            }
            WriteResult::AlreadyExists => (),
            WriteResult::RootNotFound => panic!("write_leaves given an invalid root"),
        };
        results.push(root_hash);
    }
    txn.commit()?;
    Ok(results)
}

fn check_pairs<'a, R, S, E>(
    correlation_id: CorrelationId,
    environment: &'a R,
    store: &S,
    root_hashes: &[Blake2bHash],
    pairs: &[(TestKey, TestValue)],
) -> Result<bool, E>
where
    R: TransactionSource<'a, Handle = S::Handle>,
    S: TrieStore<TestKey, TestValue>,
    S::Error: From<R::Error>,
    E: From<R::Error> + From<S::Error> + From<contract_ffi::bytesrepr::Error>,
{
    let txn = environment.create_read_txn()?;
    for (index, root_hash) in root_hashes.iter().enumerate() {
        for (key, value) in &pairs[..=index] {
            let result = read::<_, _, _, _, E>(correlation_id, &txn, store, root_hash, key)?;
            if ReadResult::Found(*value) != result {
                return Ok(false);
            }
        }
    }
    Ok(true)
}

fn lmdb_roundtrip_succeeds(pairs: &[(TestKey, TestValue)]) -> bool {
    let correlation_id = CorrelationId::new();
    let (root_hash, tries) = TEST_TRIE_GENERATORS[0]().unwrap();
    let context = LmdbTestContext::new(&tries).unwrap();
    let mut states_to_check = vec![];

    let root_hashes = write_pairs::<_, _, error::Error>(
        correlation_id,
        &context.environment,
        &context.store,
        &root_hash,
        pairs,
    )
    .unwrap();

    states_to_check.extend(root_hashes);

    check_pairs::<_, _, error::Error>(
        correlation_id,
        &context.environment,
        &context.store,
        &states_to_check,
        &pairs,
    )
    .unwrap()
}

fn in_memory_roundtrip_succeeds(pairs: &[(TestKey, TestValue)]) -> bool {
    let correlation_id = CorrelationId::new();
    let (root_hash, tries) = TEST_TRIE_GENERATORS[0]().unwrap();
    let context = InMemoryTestContext::new(&tries).unwrap();
    let mut states_to_check = vec![];

    let root_hashes = write_pairs::<_, _, in_memory::Error>(
        correlation_id,
        &context.environment,
        &context.store,
        &root_hash,
        pairs,
    )
    .unwrap();

    states_to_check.extend(root_hashes);

    check_pairs::<_, _, in_memory::Error>(
        correlation_id,
        &context.environment,
        &context.store,
        &states_to_check,
        &pairs,
    )
    .unwrap()
}

fn test_key_arb() -> impl Strategy<Value = TestKey> {
    array::uniform7(any::<u8>()).prop_map(TestKey)
}

fn test_value_arb() -> impl Strategy<Value = TestValue> {
    array::uniform6(any::<u8>()).prop_map(TestValue)
}

proptest! {
    #[test]
    fn prop_in_memory_roundtrip_succeeds(inputs in vec((test_key_arb(), test_value_arb()), get_range())) {
        assert!(in_memory_roundtrip_succeeds(&inputs));
    }

    #[test]
    fn prop_lmdb_roundtrip_succeeds(inputs in vec((test_key_arb(), test_value_arb()), get_range())) {
        assert!(lmdb_roundtrip_succeeds(&inputs));
    }
}
