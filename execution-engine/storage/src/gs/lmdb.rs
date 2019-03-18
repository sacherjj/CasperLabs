use common::key::Key;
use common::value::Value;
use error::Error;
use gs::{DbReader, TrackingCopy};
use history::*;
use rkv::store::single::SingleStore;
use rkv::Rkv;
use shared::newtypes::Blake2bHash;
use std::collections::HashMap;
use std::fmt;
use std::path::Path;
use std::sync::{Arc, RwLock};
use transform::Transform;

#[allow(dead_code)]
pub struct LmdbGs {
    store: SingleStore,
    env: Arc<RwLock<Rkv>>,
}

impl LmdbGs {
    pub fn new(_p: &Path) -> Result<LmdbGs, Error> {
        unimplemented!()
    }

    pub fn read(&self, _k: &Key) -> Result<Option<Value>, Error> {
        unimplemented!()
    }

    pub fn write<'a, I>(&self, mut _kvs: I) -> Result<(), Error>
    where
        I: Iterator<Item = (Key, &'a Value)>,
    {
        unimplemented!()
    }

    pub fn write_single(&self, k: Key, v: &Value) -> Result<(), Error> {
        let iterator = std::iter::once((k, v));
        self.write(iterator)
    }
}

impl DbReader for LmdbGs {
    fn get(&self, k: &Key) -> Result<Option<Value>, Error> {
        // TODO: The `Reader` should really be static for the DbReader instance,
        // i.e. just by creating a DbReader for LMDB it should create a `Reader`
        // to go with it. This would prevent the database from being modified while
        // another process was expecting to be able to read it.
        self.read(k)
    }
}

impl History for LmdbGs {
    type Error = Error;
    type Reader = Self;

    fn checkout(
        &self,
        _prestate_hash: Blake2bHash,
    ) -> Result<Option<TrackingCopy<Self::Reader>>, Self::Error> {
        unimplemented!("LMDB History not implemented")
    }

    fn commit(
        &mut self,
        _prestate_hash: Blake2bHash,
        _effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, Self::Error> {
        unimplemented!("LMDB History not implemented")
    }
}

impl fmt::Debug for LmdbGs {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "LMDB({:?})", self.env)
    }
}
