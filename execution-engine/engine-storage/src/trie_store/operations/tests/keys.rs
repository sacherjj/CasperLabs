mod partial_tries {
    use engine_shared::newtypes::CorrelationId;

    use crate::{
        error::{self, in_memory},
        transaction_source::{Transaction, TransactionSource},
        trie::Trie,
        trie_store::operations::{
            self,
            tests::{
                InMemoryTestContext, LmdbTestContext, TestKey, TestValue, TEST_LEAVES,
                TEST_TRIE_GENERATORS,
            },
        },
    };

    #[test]
    fn lmdb_keys_from_n_leaf_partial_trie_had_expected_results() {
        for (num_leaves, generator) in TEST_TRIE_GENERATORS.iter().enumerate() {
            let correlation_id = CorrelationId::new();
            let (root_hash, tries) = generator().unwrap();
            let context = LmdbTestContext::new(&tries).unwrap();
            let test_leaves = TEST_LEAVES;
            let (used, _) = test_leaves.split_at(num_leaves);

            let expected = {
                let mut tmp = used
                    .iter()
                    .filter_map(Trie::key)
                    .cloned()
                    .collect::<Vec<TestKey>>();
                tmp.sort();
                tmp
            };
            let actual = {
                let txn = context.environment.create_read_txn().unwrap();
                let mut tmp = operations::keys::<TestKey, TestValue, _, _, error::Error>(
                    correlation_id,
                    &txn,
                    &context.store,
                    &root_hash,
                )
                .unwrap();
                txn.commit().unwrap();
                tmp.sort();
                tmp
            };
            assert_eq!(actual, expected);
        }
    }

    #[test]
    fn in_memory_keys_from_n_leaf_partial_trie_had_expected_results() {
        for (num_leaves, generator) in TEST_TRIE_GENERATORS.iter().enumerate() {
            let correlation_id = CorrelationId::new();
            let (root_hash, tries) = generator().unwrap();
            let context = InMemoryTestContext::new(&tries).unwrap();
            let test_leaves = TEST_LEAVES;
            let (used, _) = test_leaves.split_at(num_leaves);

            let expected = {
                let mut tmp = used
                    .iter()
                    .filter_map(Trie::key)
                    .cloned()
                    .collect::<Vec<TestKey>>();
                tmp.sort();
                tmp
            };
            let actual = {
                let txn = context.environment.create_read_txn().unwrap();
                let mut tmp = operations::keys::<TestKey, TestValue, _, _, in_memory::Error>(
                    correlation_id,
                    &txn,
                    &context.store,
                    &root_hash,
                )
                .unwrap();
                txn.commit().unwrap();
                tmp.sort();
                tmp
            };
            assert_eq!(actual, expected);
        }
    }
}

mod full_tries {
    use engine_shared::newtypes::{Blake2bHash, CorrelationId};

    use crate::{
        error::in_memory,
        transaction_source::{Transaction, TransactionSource},
        trie::Trie,
        trie_store::operations::{
            self,
            tests::{
                InMemoryTestContext, TestKey, TestValue, EMPTY_HASHED_TEST_TRIES, TEST_LEAVES,
                TEST_TRIE_GENERATORS,
            },
        },
    };

    #[test]
    fn in_memory_keys_from_n_leaf_full_trie_had_expected_results() {
        let correlation_id = CorrelationId::new();
        let context = InMemoryTestContext::new(EMPTY_HASHED_TEST_TRIES).unwrap();
        let mut states: Vec<Blake2bHash> = Vec::new();

        for (state_index, generator) in TEST_TRIE_GENERATORS.iter().enumerate() {
            let (root_hash, tries) = generator().unwrap();
            context.update(&tries).unwrap();
            states.push(root_hash);

            for (num_leaves, state) in states[..state_index].iter().enumerate() {
                let test_leaves = TEST_LEAVES;
                let (used, _unused) = test_leaves.split_at(num_leaves);

                let expected = {
                    let mut tmp = used
                        .iter()
                        .filter_map(Trie::key)
                        .cloned()
                        .collect::<Vec<TestKey>>();
                    tmp.sort();
                    tmp
                };
                let actual = {
                    let txn = context.environment.create_read_txn().unwrap();
                    let mut tmp = operations::keys::<TestKey, TestValue, _, _, in_memory::Error>(
                        correlation_id,
                        &txn,
                        &context.store,
                        &state,
                    )
                    .unwrap();
                    txn.commit().unwrap();
                    tmp.sort();
                    tmp
                };
                assert_eq!(actual, expected);
            }
        }
    }
}
