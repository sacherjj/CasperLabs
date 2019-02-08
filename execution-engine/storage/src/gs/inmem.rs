use crate::op::Op;
use crate::transform::Transform;
use crate::utils::add;
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::*;
use rand::{FromEntropy, RngCore};
use std::collections::{BTreeMap, HashMap};

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

impl GlobalState<InMemTC> for InMemGS {
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
    fn get(&self, k: &Key) -> Result<&Value, Error> {
        match self.store.get(k) {
            None => Err(Error::KeyNotFound { key: *k }),
            Some(v) => Ok(v),
        }
    }
    fn tracking_copy(&self) -> InMemTC {
        InMemTC {
            store: self.store.clone(), //TODO: make more efficient
            ops: HashMap::new(),
            fns: HashMap::new(),
            rng: rand::rngs::StdRng::from_entropy(),
        }
    }
}

pub struct InMemTC {
    store: HashMap<Key, Value>,
    ops: HashMap<Key, Op>,
    fns: HashMap<Key, Transform>,
    rng: rand::rngs::StdRng,
}

impl TrackingCopy for InMemTC {
    fn new_uref(&mut self) -> Key {
        let mut key = [0u8; 32];
        self.rng.fill_bytes(&mut key);
        Key::URef(key)
    }

    fn read(&mut self, k: Key) -> Result<&Value, Error> {
        let maybe_value = self.store.get(&k);
        match maybe_value {
            None => Err(Error::KeyNotFound { key: k }),
            Some(v) => {
                add(&mut self.ops, k, Op::Read);
                Ok(v)
            }
        }
    }
    fn write(&mut self, k: Key, v: Value) -> Result<(), Error> {
        let _ = self.store.insert(k, v.clone());
        add(&mut self.ops, k, Op::Write);
        add(&mut self.fns, k, Transform::Write(v));
        Ok(())
    }
    fn add(&mut self, k: Key, v: Value) -> Result<(), Error> {
        let maybe_curr = self.store.remove(&k);
        match maybe_curr {
            None => Err(Error::KeyNotFound { key: k }),
            Some(curr) => {
                let t = match v {
                    Value::Int32(i) => Ok(Transform::AddInt32(i)),
                    Value::NamedKey(n, k) => {
                        let mut map = BTreeMap::new();
                        map.insert(n, k);
                        Ok(Transform::AddKeys(map))
                    }
                    other => Err(Error::TypeMismatch {
                        expected: "Int32 or NamedKey".to_string(),
                        found: other.type_string(),
                    }),
                }?;
                let new_value = t.clone().apply(curr)?;
                let _ = self.store.insert(k, new_value);
                add(&mut self.ops, k, Op::Add);
                add(&mut self.fns, k, t);
                Ok(())
            }
        }
    }

    fn effect(&self) -> ExecutionEffect {
        ExecutionEffect(self.ops.clone(), self.fns.clone())
    }
}
