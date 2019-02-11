use crate::transform::Transform;
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::*;
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

impl GlobalState for InMemGS {
    fn apply(&mut self, k: Key, t: Transform) -> Result<(), Error> {
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
    }
    fn tracking_copy(&self) -> Result<TrackingCopy<Self>, Error> {
        Ok(TrackingCopy::new(self))
    }
}

