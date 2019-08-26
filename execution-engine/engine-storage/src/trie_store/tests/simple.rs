use lmdb::DatabaseFlags;
use tempfile::tempdir;

use contract_ffi::bytesrepr::ToBytes;
use engine_shared::newtypes::Blake2bHash;

use super::TestData;
use crate::error::{self, in_memory};
use crate::transaction_source::in_memory::InMemoryEnvironment;
use crate::transaction_source::lmdb::LmdbEnvironment;
use crate::transaction_source::{Transaction, TransactionSource};
use crate::trie::Trie;
use crate::trie_store::in_memory::InMemoryTrieStore;
use crate::trie_store::lmdb::LmdbTrieStore;
use crate::trie_store::TrieStore;
use crate::TEST_MAP_SIZE;

fn put_succeeds<'a, K, V, S, X, E>(
    store: &S,
    transaction_source: &'a X,
    items: &[TestData<K, V>],
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
fn in_memory_put_succeeds() {
    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);
    let data = &super::create_data()[0..1];

    assert!(put_succeeds::<_, _, _, _, in_memory::Error>(&store, &env, data).is_ok());
}

#[test]
fn lmdb_put_succeeds() {
    let tmp_dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();
    let data = &super::create_data()[0..1];

    assert!(put_succeeds::<_, _, _, _, error::Error>(&store, &env, data).is_ok());

    tmp_dir.close().unwrap();
}

fn put_get_succeeds<'a, K, V, S, X, E>(
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
    let ret = super::get_many::<_, _, _, _, E>(&txn, store, &keys);
    txn.commit()?;
    ret
}

#[test]
fn in_memory_put_get_succeeds() {
    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);
    let data = &super::create_data()[0..1];

    let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
        data.to_vec().into_iter().map(|TestData(_, v)| v).collect();

    assert_eq!(
        expected,
        put_get_succeeds::<_, _, _, _, in_memory::Error>(&store, &env, data)
            .expect("put_get_succeeds failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
    )
}

#[test]
fn lmdb_put_get_succeeds() {
    let tmp_dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();
    let data = &super::create_data()[0..1];

    let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
        data.to_vec().into_iter().map(|TestData(_, v)| v).collect();

    assert_eq!(
        expected,
        put_get_succeeds::<_, _, _, _, error::Error>(&store, &env, data)
            .expect("put_get_succeeds failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
    );

    tmp_dir.close().unwrap();
}

#[test]
fn in_memory_put_get_many_succeeds() {
    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);
    let data = super::create_data();

    let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
        data.to_vec().into_iter().map(|TestData(_, v)| v).collect();

    assert_eq!(
        expected,
        put_get_succeeds::<_, _, _, _, in_memory::Error>(&store, &env, &data)
            .expect("put_get failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
    )
}

#[test]
fn lmdb_put_get_many_succeeds() {
    let tmp_dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();
    let data = super::create_data();

    let expected: Vec<Trie<Vec<u8>, Vec<u8>>> =
        data.to_vec().into_iter().map(|TestData(_, v)| v).collect();

    assert_eq!(
        expected,
        put_get_succeeds::<_, _, _, _, error::Error>(&store, &env, &data)
            .expect("put_get failed")
            .into_iter()
            .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
            .expect("one of the outputs was empty")
    );

    tmp_dir.close().unwrap();
}

fn uncommitted_read_write_txn_does_not_persist<'a, K, V, S, X, E>(
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
    {
        let mut txn: X::ReadWriteTransaction = transaction_source.create_read_write_txn()?;
        super::put_many::<_, _, _, _, E>(&mut txn, store, items)?;
    }
    {
        let txn: X::ReadTransaction = transaction_source.create_read_txn()?;
        let keys: Vec<&Blake2bHash> = items.iter().map(|TestData(k, _)| k).collect();
        let ret = super::get_many::<_, _, _, _, E>(&txn, store, &keys);
        txn.commit()?;
        ret
    }
}

#[test]
fn in_memory_uncommitted_read_write_txn_does_not_persist() {
    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);
    let data = super::create_data();

    assert_eq!(
        None,
        uncommitted_read_write_txn_does_not_persist::<_, _, _, _, in_memory::Error>(
            &store, &env, &data
        )
        .expect("uncommitted_read_write_txn_does_not_persist failed")
        .into_iter()
        .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
    )
}

#[test]
fn lmdb_uncommitted_read_write_txn_does_not_persist() {
    let tmp_dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();
    let data = super::create_data();

    assert_eq!(
        None,
        uncommitted_read_write_txn_does_not_persist::<_, _, _, _, error::Error>(
            &store, &env, &data
        )
        .expect("uncommitted_read_write_txn_does_not_persist failed")
        .into_iter()
        .collect::<Option<Vec<Trie<Vec<u8>, Vec<u8>>>>>()
    );

    tmp_dir.close().unwrap();
}

fn read_write_transaction_does_not_block_read_transaction<'a, X, E>(
    transaction_source: &'a X,
) -> Result<(), E>
where
    X: TransactionSource<'a>,
    E: From<X::Error>,
{
    let read_write_txn = transaction_source.create_read_write_txn()?;
    let read_txn = transaction_source.create_read_txn()?;
    read_write_txn.commit()?;
    read_txn.commit()?;
    Ok(())
}

#[test]
fn in_memory_read_write_transaction_does_not_block_read_transaction() {
    let env = InMemoryEnvironment::new();

    assert!(
        read_write_transaction_does_not_block_read_transaction::<_, in_memory::Error>(&env).is_ok()
    )
}

#[test]
fn lmdb_read_write_transaction_does_not_block_read_transaction() {
    let dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();

    assert!(read_write_transaction_does_not_block_read_transaction::<_, error::Error>(&env).is_ok())
}

fn reads_are_isolated<'a, S, X, E>(store: &S, env: &'a X) -> Result<(), E>
where
    S: TrieStore<Vec<u8>, Vec<u8>>,
    X: TransactionSource<'a, Handle = S::Handle>,
    S::Error: From<X::Error>,
    E: From<S::Error> + From<X::Error> + From<contract_ffi::bytesrepr::Error>,
{
    let TestData(leaf_1_hash, leaf_1) = &super::create_data()[0..1][0];

    {
        let read_txn_1 = env.create_read_txn()?;
        let result = store.get(&read_txn_1, &leaf_1_hash)?;
        assert_eq!(result, None);

        {
            let mut write_txn = env.create_read_write_txn()?;
            store.put(&mut write_txn, &leaf_1_hash, &leaf_1)?;
            write_txn.commit()?;
        }

        let result = store.get(&read_txn_1, &leaf_1_hash)?;
        read_txn_1.commit()?;
        assert_eq!(result, None);
    }

    {
        let read_txn_2 = env.create_read_txn()?;
        let result = store.get(&read_txn_2, &leaf_1_hash)?;
        read_txn_2.commit()?;
        assert_eq!(result, Some(leaf_1.to_owned()));
    }

    Ok(())
}

#[test]
fn in_memory_reads_are_isolated() {
    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);

    assert!(reads_are_isolated::<_, _, in_memory::Error>(&store, &env).is_ok())
}

#[test]
fn lmdb_reads_are_isolated() {
    let dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

    assert!(reads_are_isolated::<_, _, error::Error>(&store, &env).is_ok())
}

fn reads_are_isolated_2<'a, S, X, E>(store: &S, env: &'a X) -> Result<(), E>
where
    S: TrieStore<Vec<u8>, Vec<u8>>,
    X: TransactionSource<'a, Handle = S::Handle>,
    S::Error: From<X::Error>,
    E: From<S::Error> + From<X::Error> + From<contract_ffi::bytesrepr::Error>,
{
    let data = super::create_data();
    let TestData(ref leaf_1_hash, ref leaf_1) = data[0];
    let TestData(ref leaf_2_hash, ref leaf_2) = data[1];

    {
        let mut write_txn = env.create_read_write_txn()?;
        store.put(&mut write_txn, leaf_1_hash, leaf_1)?;
        write_txn.commit()?;
    }

    {
        let read_txn_1 = env.create_read_txn()?;
        {
            let mut write_txn = env.create_read_write_txn()?;
            store.put(&mut write_txn, leaf_2_hash, leaf_2)?;
            write_txn.commit()?;
        }
        let result = store.get(&read_txn_1, leaf_1_hash)?;
        read_txn_1.commit()?;
        assert_eq!(result, Some(leaf_1.to_owned()));
    }

    {
        let read_txn_2 = env.create_read_txn()?;
        let result = store.get(&read_txn_2, leaf_2_hash)?;
        read_txn_2.commit()?;
        assert_eq!(result, Some(leaf_2.to_owned()));
    }

    Ok(())
}

#[test]
fn in_memory_reads_are_isolated_2() {
    let env = InMemoryEnvironment::new();
    let store = InMemoryTrieStore::new(&env);

    assert!(reads_are_isolated_2::<_, _, in_memory::Error>(&store, &env).is_ok())
}

#[test]
fn lmdb_reads_are_isolated_2() {
    let dir = tempdir().unwrap();
    let env = LmdbEnvironment::new(&dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap();
    let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();

    assert!(reads_are_isolated_2::<_, _, error::Error>(&store, &env).is_ok())
}
