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

    fn get(&mut self, k: &Key) -> Result<Value, Error> {
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

#[cfg(test)]
mod tests {
    use common::key::Key;
    use common::value::Value;
    use error::Error;
    use gs::{DbReader, TrackingCopy};
    use op::Op;
    use std::cell::Cell;
    use std::collections::BTreeMap;
    use transform::Transform;

    struct CountingDb {
        count: Cell<i32>,
        value: Option<Value>,
    }

    impl CountingDb {
        fn new() -> CountingDb {
            CountingDb {
                count: Cell::new(0),
                value: None,
            }
        }

        fn new_init(v: Value) -> CountingDb {
            CountingDb {
                count: Cell::new(0),
                value: Some(v),
            }
        }
    }

    impl DbReader for CountingDb {
        fn get(&self, _k: &Key) -> Result<Value, Error> {
            let count = self.count.get();
            let value = match self.value {
                Some(ref v) => v.clone(),
                None => Value::Int32(count),
            };
            self.count.set(count + 1);
            Ok(value)
        }
    }

    #[test]
    fn tracking_copy_new() {
        let db = CountingDb::new();
        let tc = TrackingCopy::new(&db);

        assert_eq!(tc.cache.is_empty(), true);
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);
    }

    #[test]
    fn tracking_copy_new_uref() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(&db);

        //`new_uref` must return a key of type uref
        assert_matches!(tc.new_uref(), Key::URef(_));
    }

    #[test]
    fn trackng_copy_caching() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(&db);
        let k = Key::Hash([0u8; 32]);

        let zero = Ok(Value::Int32(0));
        //first read
        let value = tc.read(k);
        assert_eq!(value, zero);

        //second read; should use cache instead
        //of going back to the DB
        let value = tc.read(k);
        let db_value = db.count.get();
        assert_eq!(value, zero);
        assert_eq!(db_value, 1);
    }

    #[test]
    fn tracking_copy_read() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(&db);
        let k = Key::Hash([0u8; 32]);

        let zero = Ok(Value::Int32(0));
        let value = tc.read(k);
        //value read correctly
        assert_eq!(value, zero);
        //read does not cause any transform
        assert_eq!(tc.fns.is_empty(), true);
        //read does produce an op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Read));
    }

    #[test]
    fn tracking_copy_write() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(&db);
        let k = Key::Hash([0u8; 32]);

        let one = Value::Int32(1);
        let write = tc.write(k, one.clone());
        assert_matches!(write, Ok(_));
        //write does not need to query the DB
        let db_value = db.count.get();
        assert_eq!(db_value, 0);
        //write creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(one)));
        //write creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_add_i32() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(&db);
        let k = Key::Hash([0u8; 32]);

        let three = Value::Int32(3);
        let add = tc.add(k, three.clone());
        assert_matches!(add, Ok(_));

        //add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        //add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }

    #[test]
    fn tracking_copy_add_named_key() {
        //DB now holds an `Account` so that we can test adding a `NamedKey`
        let account = common::value::Account::new([0u8; 32], 0u64, BTreeMap::new());
        let db = CountingDb::new_init(Value::Acct(account));
        let mut tc = TrackingCopy::new(&db);
        let k = Key::Hash([0u8; 32]);

        let named_key = Value::NamedKey("test".to_string(), tc.new_uref());
        let map = {
          let mut builder: BTreeMap<String, Key> = BTreeMap::new();
          //This is written as an `if`, but it is clear from the line
          //where `named_key` is defined that it will alwways match
          if let Value::NamedKey(name, key) = named_key.clone() {
            builder.insert(name, key);
          }
          builder
        };

        //adding the wrong type should fail
        let failed_add = tc.add(k, Value::Int32(3));
        assert_matches!(failed_add, Err(Error::TypeMismatch{ .. }));
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);
        

        //adding correct type works
        let add = tc.add(k, named_key);
        assert_matches!(add, Ok(_));
        //add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map)));
        //add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }
}
