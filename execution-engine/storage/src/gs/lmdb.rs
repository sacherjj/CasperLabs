use common::bytesrepr::{deserialize, ToBytes};
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::DbReader;
use gs::{GlobalState, TrackingCopy};
use rkv::store::single::SingleStore;
use rkv::{Manager, Reader, Rkv, StoreOptions, Writer};
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
            .map_err(|_| Error::RkvError)
            .and_then(|mut r| r.get_or_create(p, Rkv::new).map_err(|e| e.into()))?;
        let store = env.read().map_err(|_| Error::RkvError).and_then(|r| {
            r.open_single(Some("global_state"), StoreOptions::create())
                .map_err(|e| e.into())
        })?;
        Ok(LmdbGs { store, env })
    }

    pub fn with_reader<A, F: Fn(&Reader) -> Result<A, Error>>(&self, txn: F) -> Result<A, Error> {
        self.env.read().map_err(|_| Error::RkvError).and_then(|r| {
            let reader = r.read()?;
            txn(&reader)
        })
    }

    pub fn with_writer<F: Fn(&mut Writer) -> Result<(), Error>>(
        &self,
        txn: F,
    ) -> Result<(), Error> {
        self.env.read().map_err(|_| Error::RkvError).and_then(|r| {
            let mut writer = r.write()?;
            let _ = txn(&mut writer)?;
            let _ = writer.commit()?;
            Ok(())
        })
    }

    pub fn read(&self, k: &Key, r: &Reader) -> Result<Value, Error> {
        let maybe_curr = self.store.get(r, k)?;

        match maybe_curr {
            None => Err(Error::KeyNotFound { key: *k }),
            Some(rkv::Value::Blob(bytes)) => {
                let value = deserialize(bytes)?;
                Ok(value)
            }
            //If we always store values as Blobs this case will never come
            //up. TODO: Use other variants of rkb::Value (e.g. I64, Str)?
            Some(_) => Err(Error::RkvError),
        }
    }

    pub fn write(&self, w: &mut Writer, k: Key, value: &Value) -> Result<(), Error> {
        let bytes = value.to_bytes();
        let _ = self.store.put(w, k, &rkv::Value::Blob(&bytes))?;
        Ok(())
    }
}

impl DbReader for LmdbGs {
    fn get(&self, k: &Key) -> Result<Value, Error> {
        //TODO: The `Reader` should really be static for the DbReader instance,
        //i.e. just by creating a DbReader for LMDB it should create a `Reader`
        //to go with it. This would prevent the database from being modified while
        //another process was expecing to be able to read it.
        let txn = |r: &Reader| self.read(k, r);
        self.with_reader(txn)
    }
}

impl GlobalState for LmdbGs {
    fn apply(&mut self, k: Key, t: Transform) -> Result<(), Error> {
        let maybe_curr = self.get(&k);
        match maybe_curr {
            Err(Error::KeyNotFound { .. }) => match t {
                Transform::Write(v) => {
                    let txn = |writer: &mut Writer| {
                        let _ = self.write(writer, k, &v)?;
                        Ok(())
                    };
                    self.with_writer(txn)
                }
                _ => Err(Error::KeyNotFound { key: k }),
            },
            Ok(curr) => {
                let new_value = t.apply(curr)?;
                let txn = |writer: &mut Writer| {
                    let _ = self.write(writer, k, &new_value)?;
                    Ok(())
                };
                self.with_writer(txn)
            }
            Err(e) => Err(e),
        }
    }
    fn tracking_copy(&self) -> Result<TrackingCopy<Self>, Error> {
        Ok(TrackingCopy::new(self))
    }
}
