//! An in-memory trie store, intended to be used for testing.
//!
//! # Usage
//!
//! ```
//! # extern crate casperlabs_engine_storage;
//! # extern crate contract_ffi;
//! # extern crate engine_shared;
//! use casperlabs_engine_storage::transaction_source::{Transaction, TransactionSource};
//! use casperlabs_engine_storage::transaction_source::in_memory::InMemoryEnvironment;
//! use casperlabs_engine_storage::trie::{Pointer, PointerBlock, Trie};
//! use casperlabs_engine_storage::trie_store::TrieStore;
//! use casperlabs_engine_storage::trie_store::in_memory::InMemoryTrieStore;
//! use contract_ffi::bytesrepr::ToBytes;
//! use engine_shared::newtypes::Blake2bHash;
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

use contract_ffi::bytesrepr::{deserialize, FromBytes, ToBytes};

use super::*;
use crate::error::in_memory::Error;
use crate::transaction_source::in_memory::InMemoryEnvironment;
use crate::transaction_source::{Readable, Writable};

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
