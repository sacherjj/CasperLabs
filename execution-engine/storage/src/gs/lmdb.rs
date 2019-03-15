use common::bytesrepr::{deserialize, ToBytes};
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::{DbReader, TrackingCopy};
use history::*;
use rkv::store::single::SingleStore;
use rkv::{Manager, Rkv, StoreOptions};
use shared::newtypes::Blake2bHash;
use std::collections::HashMap;
use std::fmt;
use std::path::Path;
use std::sync::{Arc, RwLock};
use transform::Transform;

pub struct LmdbGs {
    store: SingleStore,
    env: Arc<RwLock<Rkv>>,
}

impl LmdbGs {
    pub fn new(p: &Path) -> Result<LmdbGs, Error> {
        let env = Manager::singleton()
            .write()
            .map_err(|_| Error::RkvError(String::from("Error while creating LMDB env.")))
            .and_then(|mut r| r.get_or_create(p, Rkv::new).map_err(Into::into))?;
        let store = env
            .read()
            .map_err(|_| Error::RkvError(String::from("Error when creating LMDB store.")))
            .and_then(|r| {
                r.open_single(Some("global_state"), StoreOptions::create())
                    .map_err(Into::into)
            })?;
        Ok(LmdbGs { store, env })
    }

    pub fn read(&self, k: &Key) -> Result<Option<Value>, Error> {
        self.env
            .read()
            .map_err(|_| Error::RkvError(String::from("Couldn't get read lock to LMDB env.")))
            .and_then(|rkv| {
                let r = rkv.read()?;
                let maybe_curr = self.store.get(&r, k)?;

                match maybe_curr {
                    None => Ok(None),
                    Some(rkv::Value::Blob(bytes)) => {
                        let value = deserialize(bytes)?;
                        Ok(Some(value))
                    }
                    // If we always store values as Blobs this case will never come
                    // up. TODO: Use other variants of rkb::Value (e.g. I64, Str)?
                    Some(_) => Err(Error::RkvError(String::from(
                        "Value stored in LMDB was != Blob",
                    ))),
                }
            })
    }

    pub fn write<'a, I>(&self, mut kvs: I) -> Result<(), Error>
    where
        I: Iterator<Item = (Key, &'a Value)>,
    {
        self.env
            .read()
            .map_err(|_| Error::RkvError(String::from("Couldn't get read lock to LMDB env.")))
            .and_then(|rkv| {
                let mut w = rkv.write()?;

                let result: Result<(), Error> = kvs.try_fold((), |_, (k, v)| {
                    let bytes = v.to_bytes();
                    self.store.put(&mut w, k, &rkv::Value::Blob(&bytes))?;
                    Ok(())
                });

                match result {
                    Ok(_) => {
                        w.commit()?;
                        Ok(())
                    }
                    e @ Err(_) => {
                        w.abort();
                        e
                    }
                }
            })
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

#[cfg(test)]
mod tests {
    use gens::gens::*;
    use gs::lmdb::LmdbGs;
    use tempfile::tempdir;

    #[test]
    fn lmdb_new() {
        // Note: the directory created by `temp_dir`
        // is automatically deleted when it goes out of scope.
        let temp_dir = tempdir().unwrap();
        let path = temp_dir.path();
        let lmdb = LmdbGs::new(&path);

        assert_matches!(lmdb, Ok(_));
    }

    #[test]
    fn lmdb_rw() {
        let temp_dir = tempdir().unwrap();
        let path = temp_dir.path();
        let lmdb = LmdbGs::new(&path).unwrap();

        proptest!(|(k in key_arb(), v in value_arb())| {
          let write = lmdb.write_single(k, &v);
          let read = lmdb.read(&k).unwrap().unwrap();
          assert_matches!(write, Ok(_));
          prop_assert_eq!(read, v.clone());
        });
    }
}
