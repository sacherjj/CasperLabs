use common::bytesrepr::ToBytes;
use history::trie::Trie;
use history::trie_store::{Readable, TrieStore, Writable};
use shared::newtypes::Blake2bHash;

fn put_many<'a, K, V, T, S, E>(
    txn: &mut T,
    store: &S,
    items: &[(Blake2bHash, Trie<K, V>)],
) -> Result<(), E>
where
    K: ToBytes,
    V: ToBytes,
    T: Writable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error>,
{
    for (hash, leaf) in items.iter() {
        store.put::<T>(txn, hash, leaf)?;
    }
    Ok(())
}

fn get_many<'a, K, V, T, S, E>(
    txn: &T,
    store: &S,
    keys: &[&Blake2bHash],
) -> Result<Vec<Option<Trie<K, V>>>, E>
where
    K: ToBytes,
    V: ToBytes,
    T: Readable<Handle = S::Handle>,
    S: TrieStore<K, V>,
    S::Error: From<T::Error>,
    E: From<S::Error>,
{
    let mut ret = Vec::new();

    for hash in keys.iter() {
        let maybe_value = store.get::<T>(txn, hash)?;
        ret.push(maybe_value);
    }
    Ok(ret)
}

mod simple {
    use common::bytesrepr::ToBytes;
    use error;
    use history::trie::{Pointer, PointerBlock, Trie};
    use history::trie_store::{Transaction, TransactionSource, TrieStore};
    use lmdb::DatabaseFlags;
    use shared::newtypes::Blake2bHash;
    use tempfile::tempdir;

    fn create_data() -> Vec<(Blake2bHash, Trie<Vec<u8>, Vec<u8>>)> {
        let leaf_1 = Trie::Leaf {
            key: vec![0u8, 0, 0],
            value: b"val_1".to_vec(),
        };
        let leaf_2 = Trie::Leaf {
            key: vec![1u8, 0, 0],
            value: b"val_2".to_vec(),
        };
        let leaf_3 = Trie::Leaf {
            key: vec![1u8, 0, 1],
            value: b"val_3".to_vec(),
        };

        let leaf_1_hash = Blake2bHash::new(&leaf_1.to_bytes());
        let leaf_2_hash = Blake2bHash::new(&leaf_2.to_bytes());
        let leaf_3_hash = Blake2bHash::new(&leaf_3.to_bytes());

        let node_2: Trie<Vec<u8>, Vec<u8>> = {
            let mut pointer_block = PointerBlock::new();
            pointer_block[0] = Some(Pointer::LeafPointer(leaf_2_hash));
            pointer_block[1] = Some(Pointer::LeafPointer(leaf_3_hash));
            let pointer_block = Box::new(pointer_block);
            Trie::Node { pointer_block }
        };

        let node_2_hash = Blake2bHash::new(&node_2.to_bytes());

        let ext_node: Trie<Vec<u8>, Vec<u8>> = {
            let affix = vec![1u8, 0];
            let pointer = Pointer::NodePointer(node_2_hash);
            Trie::Extension { affix, pointer }
        };

        let ext_node_hash = Blake2bHash::new(&ext_node.to_bytes());

        let node_1: Trie<Vec<u8>, Vec<u8>> = {
            let mut pointer_block = PointerBlock::new();
            pointer_block[0] = Some(Pointer::LeafPointer(leaf_1_hash));
            pointer_block[1] = Some(Pointer::NodePointer(ext_node_hash));
            let pointer_block = Box::new(pointer_block);
            Trie::Node { pointer_block }
        };

        let node_1_hash = Blake2bHash::new(&node_1.to_bytes());

        vec![
            (leaf_1_hash, leaf_1),
            (leaf_2_hash, leaf_2),
            (leaf_3_hash, leaf_3),
            (node_1_hash, node_1),
            (node_2_hash, node_2),
            (ext_node_hash, ext_node),
        ]
    }

    fn trie_store_put<'a, K, V, S, X, E>(
        store: &S,
        transaction_source: &'a X,
        items: &[(Blake2bHash, Trie<K, V>)],
    ) -> Result<(), E>
    where
        K: ToBytes,
        V: ToBytes,
        S: TrieStore<K, V>,
        X: TransactionSource<'a, Handle = S::Handle>,
        S::Error: From<X::Error>,
        E: From<S::Error> + From<X::Error>,
    {
        let mut txn: X::ReadWriteTransaction = transaction_source.create_read_write_txn()?;
        let ret = super::put_many(&mut txn, store, items);
        txn.commit()?;
        ret
    }

    #[test]
    fn in_memory_trie_store_put() {
        use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};

        let env = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&env);

        let data = &create_data()[0..1];

        assert_eq!(
            Ok(()),
            trie_store_put::<
                Vec<u8>,
                Vec<u8>,
                InMemoryTrieStore,
                InMemoryEnvironment,
                in_memory::Error,
            >(&store, &env, data)
        )
    }

    #[test]
    fn lmdb_trie_store_put() {
        use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

        let tmp_dir = tempdir().unwrap();
        let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf()).unwrap();
        let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

        let data = &create_data()[0..1];

        assert_eq!(
            Ok(()),
            trie_store_put::<Vec<u8>, Vec<u8>, LmdbTrieStore, LmdbEnvironment, error::Error>(
                &store, &env, data
            )
        );

        tmp_dir.close().unwrap();
    }

    fn trie_store_put_get<'a, K, V, S, X, E>(
        store: &S,
        transaction_source: &'a X,
        items: &[(Blake2bHash, Trie<K, V>)],
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
        super::put_many::<K, V, X::ReadWriteTransaction, S, E>(&mut txn, store, items)?;
        let keys: Vec<&Blake2bHash> = items.iter().map(|(k, _)| k).collect();
        let ret = super::get_many::<K, V, X::ReadWriteTransaction, S, E>(&txn, store, &keys);
        txn.commit()?;
        ret
    }

    #[test]
    fn in_memory_trie_store_put_get() {
        use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};

        let env = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&env);

        let data = &create_data()[0..1];

        let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
            data.to_vec().into_iter().map(|(_, v)| v).collect();

        assert_eq!(
            expected,
            trie_store_put_get::<
                Vec<u8>,
                Vec<u8>,
                InMemoryTrieStore,
                InMemoryEnvironment,
                in_memory::Error,
            >(&store, &env, data)
            .expect("trie_store_put_get failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
        )
    }

    #[test]
    fn lmdb_trie_store_put_get() {
        use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

        let tmp_dir = tempdir().unwrap();
        let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf()).unwrap();
        let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

        let data = &create_data()[0..1];

        let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
            data.to_vec().into_iter().map(|(_, v)| v).collect();

        assert_eq!(
            expected,
            trie_store_put_get::<Vec<u8>, Vec<u8>, LmdbTrieStore, LmdbEnvironment, error::Error>(
                &store, &env, data
            )
            .expect("trie_store_put_get failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
        );

        tmp_dir.close().unwrap();
    }

    #[test]
    fn in_memory_trie_store_put_get_many() {
        use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};

        let env = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&env);

        let data = create_data();

        let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
            data.to_vec().into_iter().map(|(_, v)| v).collect();

        assert_eq!(
            expected,
            trie_store_put_get::<
                Vec<u8>,
                Vec<u8>,
                InMemoryTrieStore,
                InMemoryEnvironment,
                in_memory::Error,
            >(&store, &env, &data)
            .expect("trie_store_put_get failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
        )
    }

    #[test]
    fn lmdb_trie_store_put_get_many() {
        use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

        let tmp_dir = tempdir().unwrap();
        let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf()).unwrap();
        let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

        let data = create_data();

        let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
            data.to_vec().into_iter().map(|(_, v)| v).collect();

        assert_eq!(
            expected,
            trie_store_put_get::<Vec<u8>, Vec<u8>, LmdbTrieStore, LmdbEnvironment, error::Error>(
                &store, &env, &data
            )
            .expect("trie_store_put_get failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
        );

        tmp_dir.close().unwrap();
    }

    fn trie_store_failed_txn<'a, K, V, S, X, E>(
        store: &S,
        transaction_source: &'a X,
        items: &[(Blake2bHash, Trie<K, V>)],
    ) -> Result<Vec<Option<Trie<K, V>>>, E>
    where
        K: ToBytes,
        V: ToBytes,
        S: TrieStore<K, V>,
        X: TransactionSource<'a, Handle = S::Handle>,
        S::Error: From<X::Error>,
        E: From<S::Error> + From<X::Error>,
    {
        {
            let mut txn: X::ReadWriteTransaction = transaction_source.create_read_write_txn()?;
            super::put_many::<K, V, X::ReadWriteTransaction, S, E>(&mut txn, store, items)?;
        }
        {
            let txn: X::ReadTransaction = transaction_source.create_read_txn()?;
            let keys: Vec<&Blake2bHash> = items.iter().map(|(k, _)| k).collect();
            let ret = super::get_many::<K, V, X::ReadTransaction, S, E>(&txn, store, &keys);
            txn.commit()?;
            ret
        }
    }

    #[test]
    fn in_memory_trie_store_failed_txn() {
        use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};

        let env = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&env);

        let data = create_data();

        assert_eq!(
            None,
            trie_store_failed_txn::<
                Vec<u8>,
                Vec<u8>,
                InMemoryTrieStore,
                InMemoryEnvironment,
                in_memory::Error,
            >(&store, &env, &data)
            .expect("trie_store_failed_txn failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
        )
    }

    #[test]
    fn lmdb_trie_store_failed_txn() {
        use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

        let tmp_dir = tempdir().unwrap();
        let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf()).unwrap();
        let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

        let data = create_data();

        assert_eq!(
            None,
            trie_store_failed_txn::<Vec<u8>, Vec<u8>, LmdbTrieStore, LmdbEnvironment, error::Error>(
                &store, &env, &data
            )
            .expect("trie_store_failed_txn failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
        );

        tmp_dir.close().unwrap();
    }
}

mod roundtrip {
    use common::bytesrepr::ToBytes;
    use common::key::Key;
    use common::value::Value;
    use history::trie::gens::trie_arb;
    use history::trie::Trie;
    use history::trie_store::{Transaction, TransactionSource, TrieStore};
    use lmdb::DatabaseFlags;
    use proptest::collection::vec;
    use proptest::prelude::proptest;
    use shared::newtypes::Blake2bHash;
    use std::ops::RangeInclusive;
    use tempfile::tempdir;

    const DEFAULT_MIN_LENGTH: usize = 1;

    const DEFAULT_MAX_LENGTH: usize = 1;

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
        items: &[(Blake2bHash, Trie<K, V>)],
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
        super::put_many::<K, V, X::ReadWriteTransaction, S, E>(&mut txn, store, items)?;
        let keys: Vec<&Blake2bHash> = items.iter().map(|(k, _)| k).collect();
        let result = super::get_many::<K, V, X::ReadWriteTransaction, S, E>(&txn, store, &keys);
        txn.commit()?;
        result
    }

    fn roundtrip_is_valid<'a, S, X, E>(
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
            let input_tuples: Vec<(Blake2bHash, Trie<Key, Value>)> = inputs
                .iter()
                .map(|trie| (Blake2bHash::new(&trie.to_bytes()), trie.to_owned()))
                .collect();
            roundtrip::<Key, Value, S, X, E>(store, transaction_source, &input_tuples)
                .expect("roundtrip failed")
                .into_iter()
                .collect::<Option<Vec<Trie<Key, Value>>>>()
                .expect("one of the outputs was empty")
        };

        outputs == inputs
    }

    fn in_memory_roundtrip(inputs: Vec<Trie<Key, Value>>) -> bool {
        use history::trie_store::in_memory::{self, InMemoryEnvironment, InMemoryTrieStore};

        let env = InMemoryEnvironment::new();
        let store = InMemoryTrieStore::new(&env);

        roundtrip_is_valid::<InMemoryTrieStore, InMemoryEnvironment, in_memory::Error>(
            &store, &env, inputs,
        )
    }

    fn lmdb_roundtrip(inputs: Vec<Trie<Key, Value>>) -> bool {
        use error;
        use history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

        let tmp_dir = tempdir().unwrap();
        let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf()).unwrap();
        let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

        let ret = roundtrip_is_valid::<LmdbTrieStore, LmdbEnvironment, error::Error>(
            &store, &env, inputs,
        );
        tmp_dir.close().unwrap();
        ret
    }

    proptest! {
        #[test]
        fn test_roundtrip(v in vec(trie_arb(), get_range())) {
            assert!(in_memory_roundtrip(v))
        }

        #[test]
        fn test_lmdb_roundtrip(v in vec(trie_arb(), get_range())) {
            assert!(lmdb_roundtrip(v))
        }
    }
}
