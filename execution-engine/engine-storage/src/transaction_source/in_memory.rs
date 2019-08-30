use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::error::in_memory::Error;
use crate::transaction_source::{Readable, Transaction, TransactionSource, Writable};

/// A marker for use in a mutex which represents the capability to perform a
/// write transaction.
struct WriteCapability;

type WriteLock<'a> = MutexGuard<'a, WriteCapability>;

type BytesMap = HashMap<Vec<u8>, Vec<u8>>;

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

    pub fn data(&self) -> Arc<Mutex<BytesMap>> {
        Arc::clone(&self.data)
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
