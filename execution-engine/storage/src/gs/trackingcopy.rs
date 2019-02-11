use common::key::Key;
use common::value::Value;
use error::Error;
use gs::{DbReader, ExecutionEffect};
use op::Op;
use rand::{FromEntropy, RngCore};
use std::collections::{BTreeMap, HashMap};
use transform::Transform;
use utils::add;

pub struct TrackingCopy<'a, R: DbReader> {
    reader: &'a R,
    cache: HashMap<Key, Value>,
    ops: HashMap<Key, Op>,
    fns: HashMap<Key, Transform>,
    rng: rand::rngs::StdRng,
}

impl<'a, R: DbReader> TrackingCopy<'a, R> {
    pub fn new(reader: &'a R) -> TrackingCopy<R> {
        TrackingCopy {
            reader,
            cache: HashMap::new(),
            ops: HashMap::new(),
            fns: HashMap::new(),
            rng: rand::rngs::StdRng::from_entropy(),
        }
    }

    pub fn new_uref(&mut self) -> Key {
        let mut key = [0u8; 32];
        self.rng.fill_bytes(&mut key);
        Key::URef(key)
    }

    pub fn get(&mut self, k: &Key) -> Result<Value, Error> {
        //TODO: this remove+insert should not be necessary, but I can't get the borrow checker to agree
        let maybe_value = self.cache.remove(k);
        match maybe_value {
            Some(value) => {
                let _ = self.cache.insert(*k, value.clone());
                Ok(value)
            }

            None => {
                let value = self.reader.get(k)?;
                let _ = self.cache.insert(*k, value.clone());
                Ok(value)
            }
        }
    }

    pub fn read(&mut self, k: Key) -> Result<Value, Error> {
        let value = self.get(&k)?;
        add(&mut self.ops, k, Op::Read);
        Ok(value)
    }
    pub fn write(&mut self, k: Key, v: Value) -> Result<(), Error> {
        let _ = self.cache.insert(k, v.clone());
        add(&mut self.ops, k, Op::Write);
        add(&mut self.fns, k, Transform::Write(v));
        Ok(())
    }
    pub fn add(&mut self, k: Key, v: Value) -> Result<(), Error> {
        let curr = self.get(&k)?;
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
        let _ = self.cache.insert(k, new_value);
        add(&mut self.ops, k, Op::Add);
        add(&mut self.fns, k, t);
        Ok(())
    }

    pub fn effect(&self) -> ExecutionEffect {
        ExecutionEffect(self.ops.clone(), self.fns.clone())
    }
}
