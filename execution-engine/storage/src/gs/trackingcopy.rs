use common::key::Key;
use common::value::Value;
use error::GlobalStateError;
use gs::{DbReader, ExecutionEffect};
use op::Op;
use std::collections::{BTreeMap, HashMap};
use transform::{Transform, TypeMismatch};
use utils::add;

#[derive(Debug)]
pub enum QueryResult {
    Success(Value),
    ValueNotFound(String),
}

pub struct TrackingCopy<R: DbReader> {
    reader: R,
    cache: HashMap<Key, Value>,
    ops: HashMap<Key, Op>,
    fns: HashMap<Key, Transform>,
}

impl<R: DbReader> TrackingCopy<R> {
    pub fn new(reader: R) -> TrackingCopy<R> {
        TrackingCopy {
            reader,
            cache: HashMap::new(),
            ops: HashMap::new(),
            fns: HashMap::new(),
        }
    }

    pub fn get(&mut self, k: &Key) -> Result<Value, GlobalStateError> {
        if let Some(value) = self.cache.get(k) {
            return Ok(value.clone());
        }
        let value = self.reader.get(k)?;
        let _ = self.cache.insert(*k, value.clone());
        Ok(value)
    }

    pub fn read(&mut self, k: Key) -> Result<Value, GlobalStateError> {
        let value = self.get(&k)?;
        add(&mut self.ops, k, Op::Read);
        Ok(value)
    }
    pub fn write(&mut self, k: Key, v: Value) -> Result<(), GlobalStateError> {
        let _ = self.cache.insert(k, v.clone());
        add(&mut self.ops, k, Op::Write);
        add(&mut self.fns, k, Transform::Write(v));
        Ok(())
    }
    pub fn add(&mut self, k: Key, v: Value) -> Result<(), GlobalStateError> {
        let curr = self.get(&k)?;
        let t = match v {
            Value::Int32(i) => Ok(Transform::AddInt32(i)),
            Value::NamedKey(n, k) => {
                let mut map = BTreeMap::new();
                map.insert(n, k);
                Ok(Transform::AddKeys(map))
            }
            other => Err(TypeMismatch::new(
                "Int32 or NamedKey".to_string(),
                other.type_string(),
            )),
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

    pub fn query(
        &mut self,
        base_key: Key,
        path: &[String],
    ) -> Result<QueryResult, GlobalStateError> {
        let base_value = self.read(base_key)?;

        let result = path.iter().enumerate().try_fold(
            base_value,
            // We encode the two possible short-circuit conditions with
            // Result<(usize, String), Error>, where the Ok(_) case corresponds to
            // QueryResult::ValueNotFound and Err(_) corresponds to
            // a storage-related error. The information in the Ok(_) case is used
            // to build an informative error message about why the query was not successful.
            |curr_value, (i, name)| -> Result<Value, Result<(usize, String), GlobalStateError>> {
                match curr_value {
                    Value::Acct(account) => {
                        if let Some(key) = account.urefs_lookup().get(name) {
                            self.read(*key).map_err(Err)
                        } else {
                            Err(Ok((i, format!("Name {} not found in Account at path:", name))))
                        }
                    }

                    Value::Contract { known_urefs, .. } => {
                        if let Some(key) = known_urefs.get(name) {
                            self.read(*key).map_err(Err)
                        } else {
                            Err(Ok((i, format!("Name {} not found in Contract at path:", name))))
                        }
                    }

                    other => Err(
                        Ok((i, format!("Name {} cannot be followed from value {:?} because it is neither an account nor contract. Value found at path:", name, other)))
                    ),
                }
            },
        );

        match result {
            Ok(value) => Ok(QueryResult::Success(value)),

            Err(Ok((i, s))) => {
                let mut error_msg = format!("{} {:?}", s, base_key);
                //include the partial path to the account/contract/value which failed
                for p in path.iter().take(i) {
                    error_msg.push_str("/");
                    error_msg.push_str(p);
                }
                Ok(QueryResult::ValueNotFound(error_msg))
            }

            Err(Err(err)) => Err(err),
        }
    }
}

#[cfg(test)]
mod tests {
    use common::key::Key;
    use common::value::{Account, Value};
    use error::{Error, GlobalStateError};
    use gens::gens::*;
    use gs::inmem::InMemGS;
    use gs::{trackingcopy::QueryResult, DbReader, TrackingCopy};
    use op::Op;
    use proptest::collection::vec;
    use proptest::prelude::*;
    use std::cell::Cell;
    use std::collections::BTreeMap;
    use std::iter;
    use std::rc::Rc;
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
        fn get(&self, _k: &Key) -> Result<Value, GlobalStateError> {
            let count = self.count.get();
            let value = match self.value {
                Some(ref v) => v.clone(),
                None => Value::Int32(count),
            };
            self.count.set(count + 1);
            Ok(value)
        }
    }

    impl DbReader for Rc<CountingDb> {
        fn get(&self, k: &Key) -> Result<Value, GlobalStateError> {
            CountingDb::get(self, k)
        }
    }

    #[test]
    fn tracking_copy_new() {
        let db = CountingDb::new();
        let tc = TrackingCopy::new(db);

        assert_eq!(tc.cache.is_empty(), true);
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);
    }

    #[test]
    fn tracking_copy_caching() {
        let db = CountingDb::new();
        let db_ref = Rc::new(db);
        let mut tc = TrackingCopy::new(db_ref.clone());
        let k = Key::Hash([0u8; 32]);

        let zero = Ok(Value::Int32(0));
        // first read
        let value = tc.read(k);
        assert_eq!(value, zero);

        // second read; should use cache instead
        // of going back to the DB
        let value = tc.read(k);
        let db_value = db_ref.count.get();
        assert_eq!(value, zero);
        assert_eq!(db_value, 1);
    }

    #[test]
    fn tracking_copy_read() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let zero = Ok(Value::Int32(0));
        let value = tc.read(k);
        // value read correctly
        assert_eq!(value, zero);
        // read does not cause any transform
        assert_eq!(tc.fns.is_empty(), true);
        // read does produce an op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Read));
    }

    #[test]
    fn tracking_copy_write() {
        let db = CountingDb::new();
        let db_ref = Rc::new(db);
        let mut tc = TrackingCopy::new(db_ref.clone());
        let k = Key::Hash([0u8; 32]);

        let one = Value::Int32(1);
        let two = Value::Int32(2);

        // writing should work
        let write = tc.write(k, one.clone());
        assert_matches!(write, Ok(_));
        // write does not need to query the DB
        let db_value = db_ref.count.get();
        assert_eq!(db_value, 0);
        // write creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(one)));
        // write creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));

        // writing again should update the values
        let write = tc.write(k, two.clone());
        assert_matches!(write, Ok(_));
        let db_value = db_ref.count.get();
        assert_eq!(db_value, 0);
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(two)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_add_i32() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let three = Value::Int32(3);

        // adding should work
        let add = tc.add(k, three.clone());
        assert_matches!(add, Ok(_));

        // add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        // add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));

        // adding again should update the values
        let add = tc.add(k, three);
        assert_matches!(add, Ok(_));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(6)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }

    #[test]
    fn tracking_copy_add_named_key() {
        // DB now holds an `Account` so that we can test adding a `NamedKey`
        let account = common::value::Account::new([0u8; 32], 0u64, BTreeMap::new());
        let db = CountingDb::new_init(Value::Acct(account));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);
        let u1 = Key::URef([1u8; 32]);
        let u2 = Key::URef([2u8; 32]);

        let named_key = Value::NamedKey("test".to_string(), u1);
        let other_named_key = Value::NamedKey("test2".to_string(), u2);
        let mut map: BTreeMap<String, Key> = BTreeMap::new();
        // This is written as an `if`, but it is clear from the line
        // where `named_key` is defined that it will always match
        if let Value::NamedKey(name, key) = named_key.clone() {
            map.insert(name, key);
        }

        // adding the wrong type should fail
        let failed_add = tc.add(k, Value::Int32(3));
        assert_matches!(failed_add, Err(Error::TransformTypeMismatch { .. }));
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);

        // adding correct type works
        let add = tc.add(k, named_key);
        assert_matches!(add, Ok(_));
        // add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map.clone())));
        // add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));

        // adding again updates the values
        if let Value::NamedKey(name, key) = other_named_key.clone() {
            map.insert(name, key);
        }
        let add = tc.add(k, other_named_key);
        assert_matches!(add, Ok(_));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }

    #[test]
    fn tracking_copy_rw() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // reading then writing should update the op
        let value = Value::Int32(3);
        let _ = tc.read(k);
        let _ = tc.write(k, value.clone());
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(value)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_ra() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // reading then adding should update the op
        let value = Value::Int32(3);
        let _ = tc.read(k);
        let _ = tc.add(k, value);
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        assert_eq!(tc.ops.len(), 1);
        // this Op is correct because Read+Add = Write
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_aw() {
        let db = CountingDb::new();
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // adding then writing should update the op
        let value = Value::Int32(3);
        let write_value = Value::Int32(7);
        let _ = tc.add(k, value);
        let _ = tc.write(k, write_value.clone());
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(write_value)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    proptest! {
        #[test]
        fn query_empty_path(k in key_arb(), missing_key in key_arb(), v in value_arb()) {
            let gs = InMemGS::new(iter::once((k, v.clone())).collect());
            let mut tc = TrackingCopy::new(gs);
            let empty_path = Vec::new();
            if let Ok(QueryResult::Success(result)) = tc.query(k, &empty_path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_key != k {
                let result = tc.query(missing_key, &empty_path);
                assert_matches!(result, Err(Error::KeyNotFound(_)));
            }
        }

        #[test]
        fn query_contract_state(
            k in key_arb(), // key state is stored at
            v in value_arb(), // value in contract state
            name in "\\PC*", // human-readable name for state
            missing_name in "\\PC*",
            body in vec(any::<u8>(), 1..1000), // contract body
            hash in u8_slice_32(), // hash for contract key
        ) {
            let mut map = BTreeMap::new();
            map.insert(k, v.clone());

            let mut known_urefs = BTreeMap::new();
            known_urefs.insert(name.clone(), k);
            let contract = Value::Contract {
                bytes: body,
                known_urefs,
            };
            let contract_key = Key::Hash(hash);
            map.insert(contract_key, contract);

            let gs = InMemGS::new(map);
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(name.clone());
            if let Ok(QueryResult::Success(result)) = tc.query(contract_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_name != name {
                let result = tc.query(contract_key, &vec!(missing_name));
                assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
            }
        }


        #[test]
        fn query_account_state(
            k in key_arb(), // key state is stored at
            v in value_arb(), // value in account state
            name in "\\PC*", // human-readable name for state
            missing_name in "\\PC*",
            pk in u8_slice_32(), // account public key
            nonce in any::<u64>(), // account nonce
            address in u8_slice_20(), // address for account key
        ) {
            let mut map = BTreeMap::new();
            map.insert(k, v.clone());

            let known_urefs = iter::once((name.clone(), k)).collect();
            let account = Account::new(
                pk,
                nonce,
                known_urefs,
            );
            let account_key = Key::Account(address);
            map.insert(account_key, Value::Acct(account));

            let gs = InMemGS::new(map);
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(name.clone());
            if let Ok(QueryResult::Success(result)) = tc.query(account_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_name != name {
                let result = tc.query(account_key, &vec!(missing_name));
                assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
            }
        }

        #[test]
        fn query_path(
            k in key_arb(), // key state is stored at
            v in value_arb(), // value in contract state
            state_name in "\\PC*", // human-readable name for state
            contract_name in "\\PC*", // human-readable name for contract
            pk in u8_slice_32(), // account public key
            nonce in any::<u64>(), // account nonce
            address in u8_slice_20(), // address for account key
            body in vec(any::<u8>(), 1..1000), //contract body
            hash in u8_slice_32(), // hash for contract key
        ) {
            let mut map = BTreeMap::new();
            map.insert(k, v.clone());

            // create contract which knows about value
            let mut contract_known_urefs = BTreeMap::new();
            contract_known_urefs.insert(state_name.clone(), k);
            let contract = Value::Contract {
                bytes: body,
                known_urefs: contract_known_urefs,
            };
            let contract_key = Key::Hash(hash);
            map.insert(contract_key, contract);

            // create account which knows about contract
            let mut account_known_urefs = BTreeMap::new();
            account_known_urefs.insert(contract_name.clone(), contract_key);
            let account = Account::new(
                pk,
                nonce,
                account_known_urefs,
            );
            let account_key = Key::Account(address);
            map.insert(account_key, Value::Acct(account));

            let gs = InMemGS::new(map);
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(contract_name, state_name);
            if let Ok(QueryResult::Success(result)) = tc.query(account_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }
        }
    }
}
