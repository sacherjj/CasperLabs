//! An in-memory trie store, intended to be used for testing.
//!
//! # Usage
//!
//! ```
//! # extern crate common;
//! # extern crate shared;
//! # extern crate storage;
//! use common::bytesrepr::ToBytes;
//! use shared::newtypes::Blake2bHash;
//! use storage::history::trie::{Pointer, PointerBlock, Trie};
//! use storage::history::trie_store::{Transaction, TransactionSource, TrieStore};
//! use storage::history::trie_store::in_memory::{InMemoryEnvironment, InMemoryTrieStore};
//!
//! // Create some leaves
//! let leaf_1 = Trie::Leaf { key: vec![0u8, 0, 0], value: b"val_1".to_vec() };
//! let leaf_2 = Trie::Leaf { key: vec![1u8, 0, 0], value: b"val_2".to_vec() };
//!
//! // Get their hashes
//! let leaf_1_hash = Blake2bHash::new(&leaf_1.to_bytes().unwrap());
//! let leaf_2_hash = Blake2bHash::new(&leaf_2.to_bytes().unwrap());
//!
//! // Create a node
//! let node: Trie<Vec<u8>, Vec<u8>> = {
//!     let mut pointer_block = PointerBlock::new();
//!     pointer_block[0] = Some(Pointer::LeafPointer(leaf_1_hash));
//!     pointer_block[1] = Some(Pointer::LeafPointer(leaf_2_hash));
//!     let pointer_block = Box::new(pointer_block);
//!     Trie::Node { pointer_block }
//! };
//!
//! // Get its hash
//! let node_hash = Blake2bHash::new(&node.to_bytes().unwrap());
//!
//! // Create the environment and the store. For both the in-memory and
//! // LMDB-backed implementations, the environment is the source of
//! // transactions.
//! let env = InMemoryEnvironment::new();
//! let store = InMemoryTrieStore::new(&env);
//!
//! // First let's create a read-write transaction, persist the values, but
//! // forget to commit the transaction.
//! {
//!     // Create a read-write transaction
//!     let mut txn = env.create_read_write_txn().unwrap();
//!
//!     // Put the values in the store
//!     store.put(&mut txn, &leaf_1_hash, &leaf_1).unwrap();
//!     store.put(&mut txn, &leaf_2_hash, &leaf_2).unwrap();
//!     store.put(&mut txn, &node_hash, &node).unwrap();
//!
//!     // Here we forget to commit the transaction before it goes out of scope
//! }
//!
//! // Now let's check to see if the values were stored
//! {
//!     // Create a read transaction
//!     let txn = env.create_read_txn().unwrap();
//!
//!     // Observe that nothing has been persisted to the store
//!     for hash in vec![&leaf_1_hash, &leaf_2_hash, &node_hash].iter() {
//!         // We need to use a type annotation here to help the compiler choose
//!         // a suitable FromBytes instance
//!         let maybe_trie: Option<Trie<Vec<u8>, Vec<u8>>> = store.get(&txn, hash).unwrap();
//!         assert!(maybe_trie.is_none());
//!     }
//!
//!     // Commit the read transaction.  Not strictly necessary, but better to be hygienic.
//!     txn.commit().unwrap();
//! }
//!
//! // Now let's try that again, remembering to commit the transaction this time
//! {
//!     // Create a read-write transaction
//!     let mut txn = env.create_read_write_txn().unwrap();
//!
//!     // Put the values in the store
//!     store.put(&mut txn, &leaf_1_hash, &leaf_1).unwrap();
//!     store.put(&mut txn, &leaf_2_hash, &leaf_2).unwrap();
//!     store.put(&mut txn, &node_hash, &node).unwrap();
//!
//!     // Commit the transaction.
//!     txn.commit().unwrap();
//! }
//!
//! // Now let's check to see if the values were stored again
//! {
//!     // Create a read transaction
//!     let txn = env.create_read_txn().unwrap();
//!
//!     // Get the values in the store
//!     assert_eq!(Some(leaf_1), store.get(&txn, &leaf_1_hash).unwrap());
//!     assert_eq!(Some(leaf_2), store.get(&txn, &leaf_2_hash).unwrap());
//!     assert_eq!(Some(node), store.get(&txn, &node_hash).unwrap());
//!
//!     // Commit the read transaction.
//!     txn.commit().unwrap();
//! }
//! ```

use super::*;
use common::bytesrepr::{self, deserialize, FromBytes, ToBytes};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};

/// A marker for use in a mutex which represents the capability to perform a
/// write transaction.
struct WriteCapability;

type WriteLock<'a> = MutexGuard<'a, WriteCapability>;

type BytesMap = HashMap<Vec<u8>, Vec<u8>>;

#[derive(Debug, Fail, PartialEq, Eq)]
pub enum Error {
    #[fail(display = "{}", _0)]
    BytesRepr(#[fail(cause)] bytesrepr::Error),

    #[fail(display = "Another thread panicked while holding a lock")]
    PoisonError,
}

impl From<bytesrepr::Error> for Error {
    fn from(e: bytesrepr::Error) -> Self {
        Error::BytesRepr(e)
    }
}

impl<T> From<std::sync::PoisonError<T>> for Error {
    fn from(_e: std::sync::PoisonError<T>) -> Self {
        Error::PoisonError
    }
}

/// A read transaction for the in-memory trie store.
pub struct InMemoryReadTransaction {
    view: BytesMap,
}

impl InMemoryReadTransaction {
    pub fn new(store: &InMemoryEnvironment) -> Result<InMemoryReadTransaction, Error> {
        let view = {
            let arc = store.data.clone();
            let lock = arc.lock()?;
            lock.to_owned()
        };
        Ok(InMemoryReadTransaction { view })
    }
}

impl Transaction for InMemoryReadTransaction {
    type Error = Error;

    type Handle = ();

    fn commit(self) -> Result<(), Self::Error> {
        Ok(())
    }
}

impl Readable for InMemoryReadTransaction {
    fn read(&self, _handle: Self::Handle, key: &[u8]) -> Result<Option<Vec<u8>>, Self::Error> {
        Ok(self.view.get(&key.to_vec()).map(ToOwned::to_owned))
    }
}

/// A read-write transaction for the in-memory trie store.
pub struct InMemoryReadWriteTransaction<'a> {
    view: BytesMap,
    store_ref: Arc<Mutex<BytesMap>>,
    _write_lock: WriteLock<'a>,
}

impl<'a> InMemoryReadWriteTransaction<'a> {
    pub fn new(store: &'a InMemoryEnvironment) -> Result<InMemoryReadWriteTransaction<'a>, Error> {
        let _write_lock = store.write_mutex.lock()?;
        let store_ref = store.data.clone();
        let view = {
            let view_lock = store_ref.lock()?;
            view_lock.to_owned()
        };
        Ok(InMemoryReadWriteTransaction {
            _write_lock,
            store_ref,
            view,
        })
    }
}

impl<'a> Transaction for InMemoryReadWriteTransaction<'a> {
    type Error = Error;

    type Handle = ();

    fn commit(self) -> Result<(), Self::Error> {
        let mut store_ref_lock = self.store_ref.lock()?;
        store_ref_lock.extend(self.view);
        Ok(())
    }
}

impl<'a> Readable for InMemoryReadWriteTransaction<'a> {
    fn read(&self, _handle: Self::Handle, key: &[u8]) -> Result<Option<Vec<u8>>, Self::Error> {
        Ok(self.view.get(&key.to_vec()).map(ToOwned::to_owned))
    }
}

impl<'a> Writable for InMemoryReadWriteTransaction<'a> {
    fn write(
        &mut self,
        _handle: Self::Handle,
        key: &[u8],
        value: &[u8],
    ) -> Result<(), Self::Error> {
        self.view.insert(key.to_vec(), value.to_vec());
        Ok(())
    }
}

/// An environment for the in-memory trie store.
pub struct InMemoryEnvironment {
    data: Arc<Mutex<BytesMap>>,
    write_mutex: Arc<Mutex<WriteCapability>>,
}

impl Default for InMemoryEnvironment {
    fn default() -> Self {
        let data = Arc::new(Mutex::new(HashMap::new()));
        let write_mutex = Arc::new(Mutex::new(WriteCapability));
        InMemoryEnvironment { data, write_mutex }
    }
}

impl InMemoryEnvironment {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn dump<K, V>(&self) -> Result<HashMap<Blake2bHash, Trie<K, V>>, in_memory::Error>
    where
        K: FromBytes,
        V: FromBytes,
    {
        self.data
            .lock()?
            .iter()
            .map(|(hash_bytes, trie_bytes)| {
                let hash: Blake2bHash = deserialize(hash_bytes)?;
                let trie: Trie<K, V> = deserialize(trie_bytes)?;
                Ok((hash, trie))
            })
            .collect::<Result<HashMap<Blake2bHash, Trie<K, V>>, bytesrepr::Error>>()
            .map_err(Into::into)
    }
}

impl<'a> TransactionSource<'a> for InMemoryEnvironment {
    type Error = Error;

    type Handle = ();

    type ReadTransaction = InMemoryReadTransaction;

    type ReadWriteTransaction = InMemoryReadWriteTransaction<'a>;

    fn create_read_txn(&'a self) -> Result<InMemoryReadTransaction, Self::Error> {
        InMemoryReadTransaction::new(self).map_err(Into::into)
    }

    fn create_read_write_txn(&'a self) -> Result<InMemoryReadWriteTransaction<'a>, Self::Error> {
        InMemoryReadWriteTransaction::new(self).map_err(Into::into)
    }
}

/// An in-memory trie store.
pub struct InMemoryTrieStore;

impl InMemoryTrieStore {
    pub fn new(_env: &InMemoryEnvironment) -> Self {
        InMemoryTrieStore
    }
}

impl<K: ToBytes + FromBytes, V: ToBytes + FromBytes> TrieStore<K, V> for InMemoryTrieStore {
    type Error = Error;

    type Handle = ();

    fn get<T>(&self, txn: &T, key: &Blake2bHash) -> Result<Option<Trie<K, V>>, Self::Error>
    where
        T: Readable<Handle = Self::Handle>,
        Self::Error: From<T::Error>,
    {
        match txn.read((), &key.to_bytes()?)? {
            None => Ok(None),
            Some(bytes) => {
                let trie = deserialize(&bytes)?;
                Ok(Some(trie))
            }
        }
    }

    fn put<T>(&self, txn: &mut T, key: &Blake2bHash, value: &Trie<K, V>) -> Result<(), Self::Error>
    where
        T: Writable<Handle = Self::Handle>,
        Self::Error: From<T::Error>,
    {
        txn.write((), &key.to_bytes()?, &value.to_bytes()?)
            .map_err(Into::into)
    }
}
