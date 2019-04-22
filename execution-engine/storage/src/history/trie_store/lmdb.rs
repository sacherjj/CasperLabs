//! An LMDB-backed trie store.
//!
//! # Usage
//!
//! ```
//! # extern crate common;
//! # extern crate lmdb;
//! # extern crate shared;
//! # extern crate storage;
//! # extern crate tempfile;
//! use common::bytesrepr::ToBytes;
//! use lmdb::DatabaseFlags;
//! use shared::newtypes::Blake2bHash;
//! use storage::history::trie::{Pointer, PointerBlock, Trie};
//! use storage::history::trie_store::{Transaction, TransactionSource, TrieStore};
//! use storage::history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};
//! use tempfile::tempdir;
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
//! let tmp_dir = tempdir().unwrap();
//! let env = LmdbEnvironment::new(&tmp_dir.path().to_path_buf()).unwrap();
//! let store = LmdbTrieStore::new(&env, None, DatabaseFlags::empty()).unwrap();
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
//!
//! tmp_dir.close().unwrap();
//! ```

use super::*;
use common::bytesrepr::{deserialize, FromBytes, ToBytes};
use error;
use lmdb::{self, Database, DatabaseFlags, Environment, RoTransaction, RwTransaction, WriteFlags};
use std::path::PathBuf;

impl<'a> Transaction for RoTransaction<'a> {
    type Error = lmdb::Error;

    type Handle = Database;

    fn commit(self) -> Result<(), Self::Error> {
        lmdb::Transaction::commit(self)
    }
}

impl<'a> Readable for RoTransaction<'a> {
    fn read(&self, handle: Self::Handle, key: &[u8]) -> Result<Option<Vec<u8>>, Self::Error> {
        match lmdb::Transaction::get(self, handle, &key) {
            Ok(bytes) => Ok(Some(bytes.to_vec())),
            Err(lmdb::Error::NotFound) => Ok(None),
            Err(e) => Err(e),
        }
    }
}

impl<'a> Transaction for RwTransaction<'a> {
    type Error = lmdb::Error;

    type Handle = Database;

    fn commit(self) -> Result<(), Self::Error> {
        <RwTransaction<'a> as lmdb::Transaction>::commit(self)
    }
}

impl<'a> Readable for RwTransaction<'a> {
    fn read(&self, handle: Self::Handle, key: &[u8]) -> Result<Option<Vec<u8>>, Self::Error> {
        match lmdb::Transaction::get(self, handle, &key) {
            Ok(bytes) => Ok(Some(bytes.to_vec())),
            Err(lmdb::Error::NotFound) => Ok(None),
            Err(e) => Err(e),
        }
    }
}

impl<'a> Writable for RwTransaction<'a> {
    fn write(&mut self, handle: Self::Handle, key: &[u8], value: &[u8]) -> Result<(), Self::Error> {
        self.put(handle, &key, &value, WriteFlags::empty())
            .map_err(Into::into)
    }
}

/// The environment for an LMDB-backed trie store.
///
/// Wraps [`lmdb::Environment`].
#[derive(Debug)]
pub struct LmdbEnvironment {
    path: PathBuf,
    env: Environment,
}

impl LmdbEnvironment {
    pub fn new(path: &PathBuf) -> Result<Self, error::Error> {
        let env = Environment::new().open(path)?;
        let path = path.to_owned();
        Ok(LmdbEnvironment { path, env })
    }

    pub fn path(&self) -> &PathBuf {
        &self.path
    }
}

impl<'a> TransactionSource<'a> for LmdbEnvironment {
    type Error = lmdb::Error;

    type Handle = Database;

    type ReadTransaction = RoTransaction<'a>;

    type ReadWriteTransaction = RwTransaction<'a>;

    fn create_read_txn(&'a self) -> Result<RoTransaction<'a>, Self::Error> {
        self.env.begin_ro_txn()
    }

    fn create_read_write_txn(&'a self) -> Result<RwTransaction<'a>, Self::Error> {
        self.env.begin_rw_txn()
    }
}

/// An LMDB-backed trie store.
///
/// Wraps [`lmdb::Database`].
#[derive(Debug, Clone)]
pub struct LmdbTrieStore {
    db: Database,
}

impl LmdbTrieStore {
    pub fn new(
        env: &LmdbEnvironment,
        name: Option<&str>,
        flags: DatabaseFlags,
    ) -> Result<Self, error::Error> {
        let db = env.env.create_db(name, flags)?;
        Ok(LmdbTrieStore { db })
    }

    pub fn open(env: &LmdbEnvironment, name: Option<&str>) -> Result<Self, error::Error> {
        let db = env.env.open_db(name)?;
        Ok(LmdbTrieStore { db })
    }
}

impl<K: ToBytes + FromBytes, V: ToBytes + FromBytes> TrieStore<K, V> for LmdbTrieStore {
    type Error = error::Error;

    type Handle = Database;

    fn get<T: Readable>(
        &self,
        txn: &T,
        key: &Blake2bHash,
    ) -> Result<Option<Trie<K, V>>, Self::Error>
    where
        T: Readable<Handle = Self::Handle>,
        Self::Error: From<T::Error>,
    {
        match txn.read(self.db, &key.to_bytes()?)? {
            None => Ok(None),
            Some(bytes) => {
                let trie = deserialize(&bytes)?;
                Ok(Some(trie))
            }
        }
    }

    fn put<T: Writable>(
        &self,
        txn: &mut T,
        key: &Blake2bHash,
        value: &Trie<K, V>,
    ) -> Result<(), Self::Error>
    where
        T: Writable<Handle = Self::Handle>,
        Self::Error: From<T::Error>,
    {
        txn.write(self.db, &key.to_bytes()?, &value.to_bytes()?)
            .map_err(Into::into)
    }
}
