//! A store for persisting [`Trie`](crate::history::trie::Trie) values at their hashes.
//!
//! See the [in_memory](in_memory/index.html#usage) and
//! [lmdb](lmdb/index.html#usage) modules for usage examples.

use history::trie::Trie;
use shared::newtypes::Blake2bHash;

pub mod lmdb;

#[cfg(test)]
pub(crate) mod in_memory;

pub(crate) mod operations;

#[cfg(test)]
mod tests;

/// A transaction which can be committed or aborted.
pub trait Transaction: Sized {
    /// An error which can occur while reading or writing during a transaction,
    /// or committing the transaction.
    type Error;

    /// An entity which is being read from or written to during a transaction.
    type Handle;

    /// Commits the transaction.
    fn commit(self) -> Result<(), Self::Error>;

    /// Aborts the transaction.
    ///
    /// Any pending operations will not be saved.
    fn abort(self) {
        unimplemented!("Abort operations should be performed in Drop implementations.")
    }
}

/// A transaction with the capability to read from a given [`Handle`](Transaction::Handle).
pub trait Readable: Transaction {
    /// Returns the value from the corresponding key from a given [`Transaction::Handle`].
    fn read(&self, handle: Self::Handle, key: &[u8]) -> Result<Option<Vec<u8>>, Self::Error>;
}

/// A transaction with the capability to write to a given [`Handle`](Transaction::Handle).
pub trait Writable: Transaction {
    /// Inserts a key-value pair into a given [`Transaction::Handle`].
    fn write(&mut self, handle: Self::Handle, key: &[u8], value: &[u8]) -> Result<(), Self::Error>;
}

/// A source of transactions e.g. values that implement [`Readable`]
/// and/or [`Writable`].
pub trait TransactionSource<'a> {
    /// An error which can occur while creating a read or read-write
    /// transaction.
    type Error;

    /// An entity which is being read from or written to during a transaction.
    type Handle;

    /// Represents the type of read transactions.
    type ReadTransaction: Readable<Error = Self::Error, Handle = Self::Handle>;

    /// Represents the type of read-write transactions.
    type ReadWriteTransaction: Readable<Error = Self::Error, Handle = Self::Handle>
        + Writable<Error = Self::Error, Handle = Self::Handle>;

    /// Creates a read transaction.
    fn create_read_txn(&'a self) -> Result<Self::ReadTransaction, Self::Error>;

    /// Creates a read-write transaction.
    fn create_read_write_txn(&'a self) -> Result<Self::ReadWriteTransaction, Self::Error>;
}

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
