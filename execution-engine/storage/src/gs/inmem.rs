use crate::transform::Transform;
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::*;
use history::*;
use std::collections::HashMap;

pub struct InMemGS {
    store: HashMap<Key, Value>,
}

impl InMemGS {
    pub fn new() -> InMemGS {
        InMemGS {
            store: HashMap::new(),
        }
    }
}

impl DbReader for InMemGS {
    fn get(&self, k: &Key) -> Result<Value, Error> {
        match self.store.get(k) {
            None => Err(Error::KeyNotFound { key: *k }),
            Some(v) => Ok(v.clone()),
        }
    }
}

impl History<Self> for InMemGS {
    fn checkout_multiple(&self, block_hashes: &[[u8; 32]]) -> Result<TrackingCopy<InMemGS>, Error> {
        unimplemented!("checkout_multiple doesn't work yet b/c TrackingCopy is able only to build on single block.")
    }

    fn checkout(&self, block_hash: [u8; 32]) -> Result<TrackingCopy<InMemGS>, Error> {
        Ok(TrackingCopy::new(self, block_hash))
    }

    fn commit(&mut self, tracking_copy: ExecutionEffect) -> Result<[u8; 32], Error> {
        tracking_copy.1.into_iter()
            .try_fold((), |_, (k, t)| {
                let maybe_curr = self.store.remove(&k);
                match maybe_curr {
                    None => match t {
                        Transform::Write(v) => {
                            let _ = self.store.insert(k, v);
                            Ok(())
                        }
                        _ => Err(Error::KeyNotFound { key: k }),
                    },
                    Some(curr) => {
                        let new_value = t.apply(curr)?;
                        let _ = self.store.insert(k, new_value);
                        Ok(())
                    }
                }
            }).and_then(|_| self.get_root_hash())
    }

    fn get_root_hash(&self) -> Result<[u8; 32], Error> {
        Ok([0u8;32])
    }
}


