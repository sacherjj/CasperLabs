//! A store for persisting [`Trie`](crate::history::trie::Trie) values at their hashes.
//!
//! See the [in_memory](in_memory/index.html#usage) and
//! [lmdb](lmdb/index.html#usage) modules for usage examples.
pub mod in_memory;
pub mod lmdb;
pub(crate) mod operations;
#[cfg(test)]
mod tests;

use engine_shared::newtypes::Blake2bHash;

use crate::transaction_source::{Readable, Writable};
use crate::trie::Trie;

/// An entity which persists [`Trie`] values at their hashes.
pub trait TrieStore<K, V> {
    /// An error which can occur while getting a value out of or putting a value
    /// into a trie store.
    type Error;

    /// Represents the underlying entity which is being read from or written to.
    type Handle;

    /// Returns the [`Trie`] value from the corresponding hash.
    fn get<T>(&self, txn: &T, key: &Blake2bHash) -> Result<Option<Trie<K, V>>, Self::Error>
    where
        T: Readable<Handle = Self::Handle>,
        Self::Error: From<T::Error>;

    /// Inserts a [`Trie`] value at a given hash.
    fn put<T>(&self, txn: &mut T, key: &Blake2bHash, value: &Trie<K, V>) -> Result<(), Self::Error>
    where
        T: Writable<Handle = Self::Handle>,
        Self::Error: From<T::Error>;
}
