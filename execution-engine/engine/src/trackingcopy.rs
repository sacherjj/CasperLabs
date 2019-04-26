use std::collections::{BTreeMap, HashMap};

use common::key::Key;
use common::value::Value;
use shared::newtypes::Validated;
use storage::global_state::{ExecutionEffect, StateReader};
use storage::op::Op;
use storage::transform::{self, Transform, TypeMismatch};
use utils::add;

#[derive(Debug)]
pub enum QueryResult {
    Success(Value),
    ValueNotFound(String),
}

pub struct TrackingCopy<R: StateReader<Key, Value>> {
    reader: R,
    cache: HashMap<Key, Value>,
    ops: HashMap<Key, Op>,
    fns: HashMap<Key, Transform>,
}

#[derive(Debug)]
pub enum AddResult {
    Success,
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
    Overflow,
}

impl<R: StateReader<Key, Value>> TrackingCopy<R> {
    pub fn new(reader: R) -> TrackingCopy<R> {
        TrackingCopy {
            reader,
            cache: HashMap::new(),
            ops: HashMap::new(),
            fns: HashMap::new(),
        }
    }

    pub fn get(&mut self, k: &Validated<Key>) -> Result<Option<Value>, R::Error> {
        if let Some(value) = self.cache.get(&**k) {
            return Ok(Some(value.clone()));
        }
        if let Some(value) = self.reader.read(&**k)? {
            self.cache.insert(**k, value.clone());
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }

    pub fn read(&mut self, k: &Validated<Key>) -> Result<Option<Value>, R::Error> {
        if let Some(value) = self.get(k)? {
            add(&mut self.ops, **k, Op::Read);
            Ok(Some(value))
        } else {
            Ok(None)
        }
    }

    pub fn write(&mut self, k: Validated<Key>, v: Validated<Value>) {
        let v_local = v.into_raw();
        let _ = self.cache.insert(*k, v_local.clone());
        add(&mut self.ops, *k, Op::Write);
        add(&mut self.fns, *k, Transform::Write(v_local));
    }

    /// Ok(None) represents missing key to which we want to "add" some value.
    /// Ok(Some(unit)) represents successful operation.
    /// Err(error) is reserved for unexpected errors when accessing global state.
    pub fn add(&mut self, k: Validated<Key>, v: Validated<Value>) -> Result<AddResult, R::Error> {
        match self.get(&k)? {
            None => Ok(AddResult::KeyNotFound(*k)),
            Some(curr) => {
                let t = match v.into_raw() {
                    Value::Int32(i) => Transform::AddInt32(i),
                    Value::UInt128(i) => Transform::AddUInt128(i),
                    Value::UInt256(i) => Transform::AddUInt256(i),
                    Value::UInt512(i) => Transform::AddUInt512(i),
                    Value::NamedKey(n, k) => {
                        let mut map = BTreeMap::new();
                        map.insert(n, k);
                        Transform::AddKeys(map)
                    }
                    other => {
                        return Ok(AddResult::TypeMismatch(TypeMismatch::new(
                            "Int32 or UInt* or NamedKey".to_string(),
                            other.type_string(),
                        )))
                    }
                };
                match t.clone().apply(curr) {
                    Ok(new_value) => {
                        let _ = self.cache.insert(*k, new_value);
                        add(&mut self.ops, *k, Op::Add);
                        add(&mut self.fns, *k, t);
                        Ok(AddResult::Success)
                    }
                    Err(transform::Error::TypeMismatch(type_mismatch)) => {
                        Ok(AddResult::TypeMismatch(type_mismatch))
                    }
                    Err(transform::Error::Overflow) => Ok(AddResult::Overflow),
                }
            }
        }
    }

    pub fn effect(&self) -> ExecutionEffect {
        ExecutionEffect(self.ops.clone(), self.fns.clone())
    }

    pub fn query(&mut self, base_key: Key, path: &[String]) -> Result<QueryResult, R::Error> {
        let validated_key = Validated::valid(base_key);
        match self.read(&validated_key)? {
            None => Ok(QueryResult::ValueNotFound(self.error_path_msg(
                base_key,
                path,
                "".to_owned(),
                0 as usize,
            ))),
            Some(base_value) => {
                let result = path.iter().enumerate().try_fold(
                    base_value,
                    // We encode the two possible short-circuit conditions with
                    // Result<(usize, String), Error>, where the Ok(_) case corresponds to
                    // QueryResult::ValueNotFound and Err(_) corresponds to
                    // a storage-related error. The information in the Ok(_) case is used
                    // to build an informative error message about why the query was not successful.
                    |curr_value, (i, name)| -> Result<Value, Result<(usize, String), R::Error>> {
                        match curr_value {
                            Value::Account(account) => {
                                if let Some(key) = account.urefs_lookup().get(name) {
                                    let validated_key = Validated::valid(*key);
                                    self.read_key_or_stop(validated_key, i)
                                } else {
                                    Err(Ok((i, format!("Name {} not found in Account at path:", name))))
                                }
                            }

                            Value::Contract(contract) => {
                                if let Some(key) = contract.urefs_lookup().get(name) {
                                    let validated_key = Validated::valid(*key);
                                    self.read_key_or_stop(validated_key, i)
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
                    Err(Ok((i, s))) => Ok(QueryResult::ValueNotFound(
                        self.error_path_msg(base_key, path, s, i),
                    )),
                    Err(Err(err)) => Err(err),
                }
            }
        }
    }

    fn read_key_or_stop(
        &mut self,
        key: Validated<Key>,
        i: usize,
    ) -> Result<Value, Result<(usize, String), R::Error>> {
        match self.read(&key) {
            // continue recursing
            Ok(Some(value)) => Ok(value),
            // key not found in the global state; stop recursing
            Ok(None) => Err(Ok((i, format!("Name {:?} not found: ", *key)))),
            // global state access error; stop recursing
            Err(error) => Err(Err(error)),
        }
    }

    fn error_path_msg(
        &self,
        key: Key,
        path: &[String],
        missing_key: String,
        missing_at_index: usize,
    ) -> String {
        let mut error_msg = format!("{} {:?}", missing_key, key);
        //include the partial path to the account/contract/value which failed
        for p in path.iter().take(missing_at_index) {
            error_msg.push_str("/");
            error_msg.push_str(p);
        }
        error_msg
    }
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::collections::BTreeMap;
    use std::iter;
    use std::rc::Rc;

    use proptest::collection::vec;
    use proptest::prelude::*;

    use common::gens::*;
    use common::key::{AccessRights, Key};
    use common::value::{Account, Contract, Value};
    use storage::global_state::inmem::InMemGS;
    use storage::global_state::StateReader;
    use storage::op::Op;
    use storage::transform::Transform;

    use super::{AddResult, QueryResult, TrackingCopy, Validated};

    struct CountingDb {
        count: Rc<Cell<i32>>,
        value: Option<Value>,
    }

    impl CountingDb {
        fn new(counter: Rc<Cell<i32>>) -> CountingDb {
            CountingDb {
                count: counter,
                value: None,
            }
        }

        fn new_init(v: Value) -> CountingDb {
            CountingDb {
                count: Rc::new(Cell::new(0)),
                value: Some(v),
            }
        }
    }

    impl StateReader<Key, Value> for CountingDb {
        type Error = !;
        fn read(&self, _key: &Key) -> Result<Option<Value>, Self::Error> {
            let count = self.count.get();
            let value = match self.value {
                Some(ref v) => v.clone(),
                None => Value::Int32(count),
            };
            self.count.set(count + 1);
            Ok(Some(value))
        }
    }

    #[test]
    fn tracking_copy_new() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let tc = TrackingCopy::new(db);

        assert_eq!(tc.cache.is_empty(), true);
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);
    }

    #[test]
    fn tracking_copy_caching() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(Rc::clone(&counter));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let zero = Value::Int32(0);
        // first read
        let value = tc.read(&Validated::valid(k)).unwrap().unwrap();
        assert_eq!(value, zero);

        // second read; should use cache instead
        // of going back to the DB
        let value = tc.read(&Validated::valid(k)).unwrap().unwrap();
        let db_value = counter.get();
        assert_eq!(value, zero);
        assert_eq!(db_value, 1);
    }

    #[test]
    fn tracking_copy_read() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(Rc::clone(&counter));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let zero = Value::Int32(0);
        let value = tc.read(&Validated::valid(k)).unwrap().unwrap();
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
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(Rc::clone(&counter));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let one = Value::Int32(1);
        let two = Value::Int32(2);

        // writing should work
        tc.write(Validated::valid(k), Validated::valid(one.clone()));
        // write does not need to query the DB
        let db_value = counter.get();
        assert_eq!(db_value, 0);
        // write creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(one)));
        // write creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));

        // writing again should update the values
        tc.write(Validated::valid(k), Validated::valid(two.clone()));
        let db_value = counter.get();
        assert_eq!(db_value, 0);
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(two)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_add_i32() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        let three = Value::Int32(3);

        // adding should work
        let add = tc.add(Validated::valid(k), Validated::valid(three.clone()));
        assert_matches!(add, Ok(_));

        // add creates a Transfrom
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        // add creates an Op
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));

        // adding again should update the values
        let add = tc.add(Validated::valid(k), Validated::valid(three));
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
        let db = CountingDb::new_init(Value::Account(account));
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);
        let u1 = Key::URef([1u8; 32], AccessRights::READ_WRITE);
        let u2 = Key::URef([2u8; 32], AccessRights::READ_WRITE);

        let named_key = Value::NamedKey("test".to_string(), u1);
        let other_named_key = Value::NamedKey("test2".to_string(), u2);
        let mut map: BTreeMap<String, Key> = BTreeMap::new();
        // This is written as an `if`, but it is clear from the line
        // where `named_key` is defined that it will always match
        if let Value::NamedKey(name, key) = named_key.clone() {
            map.insert(name, key);
        }

        // adding the wrong type should fail
        let failed_add = tc.add(Validated::valid(k), Validated::valid(Value::Int32(3)));
        assert_matches!(failed_add, Ok(AddResult::TypeMismatch(_)));
        assert_eq!(tc.ops.is_empty(), true);
        assert_eq!(tc.fns.is_empty(), true);

        // adding correct type works
        let add = tc.add(Validated::valid(k), Validated::valid(named_key));
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
        let add = tc.add(Validated::valid(k), Validated::valid(other_named_key));
        assert_matches!(add, Ok(_));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Add));
    }

    #[test]
    fn tracking_copy_rw() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // reading then writing should update the op
        let value = Value::Int32(3);
        let _ = tc.read(&Validated::valid(k));
        tc.write(Validated::valid(k), Validated::valid(value.clone()));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::Write(value)));
        assert_eq!(tc.ops.len(), 1);
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_ra() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // reading then adding should update the op
        let value = Value::Int32(3);
        let _ = tc.read(&Validated::valid(k));
        let _ = tc.add(Validated::valid(k), Validated::valid(value));
        assert_eq!(tc.fns.len(), 1);
        assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
        assert_eq!(tc.ops.len(), 1);
        // this Op is correct because Read+Add = Write
        assert_eq!(tc.ops.get(&k), Some(&Op::Write));
    }

    #[test]
    fn tracking_copy_aw() {
        let counter = Rc::new(Cell::new(0));
        let db = CountingDb::new(counter);
        let mut tc = TrackingCopy::new(db);
        let k = Key::Hash([0u8; 32]);

        // adding then writing should update the op
        let value = Value::Int32(3);
        let write_value = Value::Int32(7);
        let _ = tc.add(Validated::valid(k), Validated::valid(value));
        tc.write(Validated::valid(k), Validated::valid(write_value.clone()));
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
                assert_matches!(result, Ok(QueryResult::ValueNotFound(_)));
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
            let contract: Value = Contract::new(body, known_urefs).into();
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
                let result = tc.query(contract_key, &[missing_name]);
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
            map.insert(account_key, Value::Account(account));

            let gs = InMemGS::new(map);
            let mut tc = TrackingCopy::new(gs);
            let path = vec!(name.clone());
            if let Ok(QueryResult::Success(result)) = tc.query(account_key, &path) {
                assert_eq!(v, result);
            } else {
                panic!("Query failed when it should not have!");
            }

            if missing_name != name {
                let result = tc.query(account_key, &[missing_name]);
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
            let contract: Value = Contract::new(body, contract_known_urefs).into();
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
            map.insert(account_key, Value::Account(account));

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
