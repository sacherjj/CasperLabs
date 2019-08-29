use std::sync::{Arc, Barrier};
use std::thread;

use tempfile::tempdir;

use super::TestData;
use crate::transaction_source::in_memory::InMemoryEnvironment;
use crate::transaction_source::lmdb::LmdbEnvironment;
use crate::transaction_source::{Transaction, TransactionSource};
use crate::trie::Trie;
use crate::trie_store::in_memory::InMemoryTrieStore;
use crate::trie_store::lmdb::LmdbTrieStore;
use crate::trie_store::TrieStore;
use crate::TEST_MAP_SIZE;

#[test]
fn lmdb_writer_mutex_does_not_collide_with_readers() {
    let dir = tempdir().unwrap();
    let env = Arc::new(LmdbEnvironment::new(&dir.path().to_path_buf(), *TEST_MAP_SIZE).unwrap());
    let store = Arc::new(LmdbTrieStore::open(&env, None).unwrap());
    let num_threads = 10;
    let barrier = Arc::new(Barrier::new(num_threads + 1));
    let mut handles = Vec::new();
    let TestData(ref leaf_1_hash, ref leaf_1) = &super::create_data()[0..1][0];

    for _ in 0..num_threads {
        let reader_env = env.clone();
        let reader_store = store.clone();
        let reader_barrier = barrier.clone();
        let leaf_1_hash = *leaf_1_hash;
        #[allow(clippy::clone_on_copy)]
        let leaf_1 = leaf_1.clone();

        handles.push(thread::spawn(move || {
            {
                let txn = reader_env.create_read_txn().unwrap();
                let result: Option<Trie<Vec<u8>, Vec<u8>>> =
                    reader_store.get(&txn, &leaf_1_hash).unwrap();
                assert_eq!(result, None);
                txn.commit().unwrap();
            }
            // wait for other reader threads to read and the main thread to
            // take a read-write transaction
            reader_barrier.wait();
            // wait for main thread to put and commit
            reader_barrier.wait();
            {
                let txn = reader_env.create_read_txn().unwrap();
                let result: Option<Trie<Vec<u8>, Vec<u8>>> =
                    reader_store.get(&txn, &leaf_1_hash).unwrap();
                txn.commit().unwrap();
                result.unwrap() == leaf_1
            }
        }));
    }

    let mut txn = env.create_read_write_txn().unwrap();
    // wait for reader threads to read
    barrier.wait();
    store.put(&mut txn, &leaf_1_hash, &leaf_1).unwrap();
    txn.commit().unwrap();
    // sync with reader threads
    barrier.wait();

    assert!(handles.into_iter().all(|b| b.join().unwrap()))
}

#[test]
fn in_memory_writer_mutex_does_not_collide_with_readers() {
    let env = Arc::new(InMemoryEnvironment::new());
    let store = Arc::new(InMemoryTrieStore::new(&env));
    let num_threads = 10;
    let barrier = Arc::new(Barrier::new(num_threads + 1));
    let mut handles = Vec::new();
    let TestData(ref leaf_1_hash, ref leaf_1) = &super::create_data()[0..1][0];

    for _ in 0..num_threads {
        let reader_env = env.clone();
        let reader_store = store.clone();
        let reader_barrier = barrier.clone();
        let leaf_1_hash = *leaf_1_hash;
        #[allow(clippy::clone_on_copy)]
        let leaf_1 = leaf_1.clone();

        handles.push(thread::spawn(move || {
            {
                let txn = reader_env.create_read_txn().unwrap();
                let result: Option<Trie<Vec<u8>, Vec<u8>>> =
                    reader_store.get(&txn, &leaf_1_hash).unwrap();
                assert_eq!(result, None);
                txn.commit().unwrap();
            }
            // wait for other reader threads to read and the main thread to
            // take a read-write transaction
            reader_barrier.wait();
            // wait for main thread to put and commit
            reader_barrier.wait();
            {
                let txn = reader_env.create_read_txn().unwrap();
                let result: Option<Trie<Vec<u8>, Vec<u8>>> =
                    reader_store.get(&txn, &leaf_1_hash).unwrap();
                txn.commit().unwrap();
                result.unwrap() == leaf_1
            }
        }));
    }

    let store = store.clone();
    let mut txn = env.create_read_write_txn().unwrap();
    // wait for reader threads to read
    barrier.wait();
    store.put(&mut txn, &leaf_1_hash, &leaf_1).unwrap();
    txn.commit().unwrap();
    // sync with reader threads
    barrier.wait();

    assert!(handles.into_iter().all(|b| b.join().unwrap()))
}
