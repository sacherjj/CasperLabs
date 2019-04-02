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
//! let leaf_1_hash = Blake2bHash::new(&leaf_1.to_bytes());
//! let leaf_2_hash = Blake2bHash::new(&leaf_2.to_bytes());
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
//! let node_hash = Blake2bHash::new(&node.to_bytes());
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
use std::ops::DerefMut;
use std::sync::{Arc, PoisonError, RwLock, RwLockReadGuard, RwLockWriteGuard};

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

type BytesMap = HashMap<Vec<u8>, Vec<u8>>;

/// A read transaction for the in-memory trie store.
pub struct InMemoryReadTransaction<'a> {
    guard: RwLockReadGuard<'a, BytesMap>,
}

impl<'a> InMemoryReadTransaction<'a> {
    pub fn new(
        store: &'a InMemoryEnvironment,
    ) -> Result<InMemoryReadTransaction<'a>, PoisonError<RwLockReadGuard<'a, BytesMap>>> {
        let guard = store.data.read()?;
        Ok(InMemoryReadTransaction { guard })
    }
}

impl<'a> Transaction for InMemoryReadTransaction<'a> {
    type Error = Error;

    type Handle = ();

    fn commit(self) -> Result<(), Self::Error> {
        Ok(())
    }
}

impl<'a> Readable for InMemoryReadTransaction<'a> {
    fn read(&self, _handle: Self::Handle, key: &[u8]) -> Result<Option<Vec<u8>>, Self::Error> {
        Ok(self.guard.get(&key.to_vec()).map(ToOwned::to_owned))
    }
}

/// A read-write transaction for the in-memory trie store.
pub struct InMemoryReadWriteTransaction<'a> {
    guard: RwLockWriteGuard<'a, BytesMap>,
    view: BytesMap,
}

impl<'a> InMemoryReadWriteTransaction<'a> {
    pub fn new(
        store: &'a InMemoryEnvironment,
    ) -> Result<InMemoryReadWriteTransaction<'a>, PoisonError<RwLockWriteGuard<'a, BytesMap>>> {
        let guard = store.data.write()?;
        let view = guard.to_owned();
        Ok(InMemoryReadWriteTransaction { guard, view })
    }
}

impl<'a> Transaction for InMemoryReadWriteTransaction<'a> {
    type Error = Error;

    type Handle = ();

    fn commit(mut self) -> Result<(), Self::Error> {
        self.guard.deref_mut().extend(self.view);
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
    data: Arc<RwLock<BytesMap>>,
}

impl Default for InMemoryEnvironment {
    fn default() -> Self {
        let data = Arc::new(RwLock::new(HashMap::new()));
        InMemoryEnvironment { data }
    }
}

impl InMemoryEnvironment {
    pub fn new() -> Self {
        Default::default()
    }
}

impl<'a> TransactionSource<'a> for InMemoryEnvironment {
    type Error = Error;

    type Handle = ();

    type ReadTransaction = InMemoryReadTransaction<'a>;

    type ReadWriteTransaction = InMemoryReadWriteTransaction<'a>;

    fn create_read_txn(&'a self) -> Result<InMemoryReadTransaction<'a>, Self::Error> {
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
        match txn.read((), &key.to_bytes())? {
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
        txn.write((), &key.to_bytes(), &value.to_bytes())
            .map_err(Into::into)
    }
}
