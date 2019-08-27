use std::cell::Cell;
use std::collections::BTreeMap;
use std::iter;
use std::rc::Rc;

use proptest::collection::vec;
use proptest::prelude::*;

use crate::engine_state::op::Op;
use contract_ffi::gens::*;
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::{
    AccountActivity, AssociatedKeys, BlockTime, PublicKey, PurseId, Weight, KEY_SIZE,
};
use contract_ffi::value::{Account, Contract, Value};
use engine_shared::newtypes::CorrelationId;
use engine_shared::transform::Transform;
use engine_storage::global_state::in_memory::InMemoryGlobalState;
use engine_storage::global_state::StateReader;

use super::meter::count_meter::Count;
use super::{AddResult, QueryResult, Validated};
use super::{TrackingCopy, TrackingCopyCache};

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
    fn read(
        &self,
        _correlation_id: CorrelationId,
        _key: &Key,
    ) -> Result<Option<Value>, Self::Error> {
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
    let correlation_id = CorrelationId::new();
    let counter = Rc::new(Cell::new(0));
    let db = CountingDb::new(Rc::clone(&counter));
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);

    let zero = Value::Int32(0);
    // first read
    let value = tc
        .read(
            correlation_id,
            &Validated::new(k, Validated::valid).unwrap(),
        )
        .unwrap()
        .unwrap();
    assert_eq!(value, zero);

    // second read; should use cache instead
    // of going back to the DB
    let value = tc
        .read(
            correlation_id,
            &Validated::new(k, Validated::valid).unwrap(),
        )
        .unwrap()
        .unwrap();
    let db_value = counter.get();
    assert_eq!(value, zero);
    assert_eq!(db_value, 1);
}

#[test]
fn tracking_copy_read() {
    let correlation_id = CorrelationId::new();
    let counter = Rc::new(Cell::new(0));
    let db = CountingDb::new(Rc::clone(&counter));
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);

    let zero = Value::Int32(0);
    let value = tc
        .read(
            correlation_id,
            &Validated::new(k, Validated::valid).unwrap(),
        )
        .unwrap()
        .unwrap();
    // value read correctly
    assert_eq!(value, zero);
    // read produces an identity transform
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::Identity));
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
    tc.write(
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(one.clone(), Validated::valid).unwrap(),
    );
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
    tc.write(
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(two.clone(), Validated::valid).unwrap(),
    );
    let db_value = counter.get();
    assert_eq!(db_value, 0);
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::Write(two)));
    assert_eq!(tc.ops.len(), 1);
    assert_eq!(tc.ops.get(&k), Some(&Op::Write));
}

#[test]
fn tracking_copy_add_i32() {
    let correlation_id = CorrelationId::new();
    let counter = Rc::new(Cell::new(0));
    let db = CountingDb::new(counter);
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);

    let three = Value::Int32(3);

    // adding should work
    let add = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(three.clone(), Validated::valid).unwrap(),
    );
    assert_matches!(add, Ok(_));

    // add creates a Transfrom
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
    // add creates an Op
    assert_eq!(tc.ops.len(), 1);
    assert_eq!(tc.ops.get(&k), Some(&Op::Add));

    // adding again should update the values
    let add = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(three, Validated::valid).unwrap(),
    );
    assert_matches!(add, Ok(_));
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(6)));
    assert_eq!(tc.ops.len(), 1);
    assert_eq!(tc.ops.get(&k), Some(&Op::Add));
}

#[test]
fn tracking_copy_add_named_key() {
    let correlation_id = CorrelationId::new();
    // DB now holds an `Account` so that we can test adding a `NamedKey`
    let associated_keys = AssociatedKeys::new(PublicKey::new([0u8; KEY_SIZE]), Weight::new(1));
    let account = contract_ffi::value::Account::new(
        [0u8; KEY_SIZE],
        0u64,
        BTreeMap::new(),
        PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
        associated_keys,
        Default::default(),
        AccountActivity::new(BlockTime(0), BlockTime(100)),
    );
    let db = CountingDb::new_init(Value::Account(account));
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);
    let u1 = Key::URef(URef::new([1u8; 32], AccessRights::READ_WRITE));
    let u2 = Key::URef(URef::new([2u8; 32], AccessRights::READ_WRITE));

    let named_key = Value::NamedKey("test".to_string(), u1);
    let other_named_key = Value::NamedKey("test2".to_string(), u2);
    let mut map: BTreeMap<String, Key> = BTreeMap::new();
    // This is written as an `if`, but it is clear from the line
    // where `named_key` is defined that it will always match
    if let Value::NamedKey(name, key) = named_key.clone() {
        map.insert(name, key);
    }

    // adding the wrong type should fail
    let failed_add = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(Value::Int32(3), Validated::valid).unwrap(),
    );
    assert_matches!(failed_add, Ok(AddResult::TypeMismatch(_)));
    assert_eq!(tc.ops.is_empty(), true);
    assert_eq!(tc.fns.is_empty(), true);

    // adding correct type works
    let add = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(named_key, Validated::valid).unwrap(),
    );
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
    let add = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(other_named_key, Validated::valid).unwrap(),
    );
    assert_matches!(add, Ok(_));
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::AddKeys(map)));
    assert_eq!(tc.ops.len(), 1);
    assert_eq!(tc.ops.get(&k), Some(&Op::Add));
}

#[test]
fn tracking_copy_rw() {
    let correlation_id = CorrelationId::new();
    let counter = Rc::new(Cell::new(0));
    let db = CountingDb::new(counter);
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);

    // reading then writing should update the op
    let value = Value::Int32(3);
    let _ = tc.read(
        correlation_id,
        &Validated::new(k, Validated::valid).unwrap(),
    );
    tc.write(
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(value.clone(), Validated::valid).unwrap(),
    );
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::Write(value)));
    assert_eq!(tc.ops.len(), 1);
    assert_eq!(tc.ops.get(&k), Some(&Op::Write));
}

#[test]
fn tracking_copy_ra() {
    let correlation_id = CorrelationId::new();
    let counter = Rc::new(Cell::new(0));
    let db = CountingDb::new(counter);
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);

    // reading then adding should update the op
    let value = Value::Int32(3);
    let _ = tc.read(
        correlation_id,
        &Validated::new(k, Validated::valid).unwrap(),
    );
    let _ = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(value, Validated::valid).unwrap(),
    );
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::AddInt32(3)));
    assert_eq!(tc.ops.len(), 1);
    // this Op is correct because Read+Add = Write
    assert_eq!(tc.ops.get(&k), Some(&Op::Write));
}

#[test]
fn tracking_copy_aw() {
    let correlation_id = CorrelationId::new();
    let counter = Rc::new(Cell::new(0));
    let db = CountingDb::new(counter);
    let mut tc = TrackingCopy::new(db);
    let k = Key::Hash([0u8; 32]);

    // adding then writing should update the op
    let value = Value::Int32(3);
    let write_value = Value::Int32(7);
    let _ = tc.add(
        correlation_id,
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(value, Validated::valid).unwrap(),
    );
    tc.write(
        Validated::new(k, Validated::valid).unwrap(),
        Validated::new(write_value.clone(), Validated::valid).unwrap(),
    );
    assert_eq!(tc.fns.len(), 1);
    assert_eq!(tc.fns.get(&k), Some(&Transform::Write(write_value)));
    assert_eq!(tc.ops.len(), 1);
    assert_eq!(tc.ops.get(&k), Some(&Op::Write));
}

proptest! {
    #[test]
    fn query_empty_path(k in key_arb(), missing_key in key_arb(), v in value_arb()) {
        let correlation_id = CorrelationId::new();
        let gs = InMemoryGlobalState::from_pairs(correlation_id, &[(k, v.to_owned())]).unwrap();
        let mut tc = TrackingCopy::new(gs);
        let empty_path = Vec::new();
        if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, k, &empty_path) {
            assert_eq!(v, result);
        } else {
            panic!("Query failed when it should not have!");
        }

        if missing_key != k {
            let result = tc.query(correlation_id, missing_key, &empty_path);
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
        let correlation_id = CorrelationId::new();
        let mut known_urefs = BTreeMap::new();
        known_urefs.insert(name.clone(), k);
        let contract: Value = Contract::new(body, known_urefs, 1).into();
        let contract_key = Key::Hash(hash);

        let gs = InMemoryGlobalState::from_pairs(
            correlation_id,
            &[(k, v.to_owned()), (contract_key, contract)]
        ).unwrap();
        let mut tc = TrackingCopy::new(gs);
        let path = vec!(name.clone());
        if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, contract_key, &path) {
            assert_eq!(v, result);
        } else {
            panic!("Query failed when it should not have!");
        }

        if missing_name != name {
            let result = tc.query(correlation_id, contract_key, &[missing_name]);
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
        address in u8_slice_32(), // address for account key
    ) {
        let correlation_id = CorrelationId::new();
        let known_urefs = iter::once((name.clone(), k)).collect();
        let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
        let associated_keys = AssociatedKeys::new(PublicKey::new(pk), Weight::new(1));
        let account = Account::new(
            pk,
            nonce,
            known_urefs,
            purse_id,
            associated_keys,
            Default::default(),
            AccountActivity::new(BlockTime(0), BlockTime(100))
        );
        let account_key = Key::Account(address);

        let gs = InMemoryGlobalState::from_pairs(
            correlation_id,
            &[(k, v.to_owned()), (account_key, Value::Account(account))],
        ).unwrap();
        let mut tc = TrackingCopy::new(gs);
        let path = vec!(name.clone());
        if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, account_key, &path) {
            assert_eq!(v, result);
        } else {
            panic!("Query failed when it should not have!");
        }

        if missing_name != name {
            let result = tc.query(correlation_id, account_key, &[missing_name]);
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
        address in u8_slice_32(), // address for account key
        body in vec(any::<u8>(), 1..1000), //contract body
        hash in u8_slice_32(), // hash for contract key
    ) {
        let correlation_id = CorrelationId::new();
        // create contract which knows about value
        let mut contract_known_urefs = BTreeMap::new();
        contract_known_urefs.insert(state_name.clone(), k);
        let contract: Value = Contract::new(body, contract_known_urefs, 1).into();
        let contract_key = Key::Hash(hash);

        // create account which knows about contract
        let mut account_known_urefs = BTreeMap::new();
        account_known_urefs.insert(contract_name.clone(), contract_key);
        let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
        let associated_keys = AssociatedKeys::new(PublicKey::new(pk), Weight::new(1));
        let account = Account::new(
            pk,
            nonce,
            account_known_urefs,
            purse_id,
            associated_keys,
            Default::default(),
            AccountActivity::new(BlockTime(0), BlockTime(100))
        );
        let account_key = Key::Account(address);

        let gs = InMemoryGlobalState::from_pairs(correlation_id, &[
            (k, v.to_owned()),
            (contract_key, contract),
            (account_key, Value::Account(account)),
        ]).unwrap();
        let mut tc = TrackingCopy::new(gs);
        let path = vec!(contract_name, state_name);
        if let Ok(QueryResult::Success(result)) = tc.query(correlation_id, account_key, &path) {
            assert_eq!(v, result);
        } else {
            panic!("Query failed when it should not have!");
        }
    }
}

#[test]
fn cache_reads_invalidation() {
    let mut tc_cache = TrackingCopyCache::new(2, Count);
    let (k1, v1) = (Key::Hash([1u8; 32]), Value::Int32(1));
    let (k2, v2) = (Key::Hash([2u8; 32]), Value::Int32(2));
    let (k3, v3) = (Key::Hash([3u8; 32]), Value::Int32(3));
    tc_cache.insert_read(k1, v1);
    tc_cache.insert_read(k2, v2.clone());
    tc_cache.insert_read(k3, v3.clone());
    assert!(tc_cache.get(&k1).is_none()); // first entry should be invalidated
    assert_eq!(tc_cache.get(&k2), Some(&v2)); // k2 and k3 should be there
    assert_eq!(tc_cache.get(&k3), Some(&v3));
}

#[test]
fn cache_writes_not_invalidated() {
    let mut tc_cache = TrackingCopyCache::new(2, Count);
    let (k1, v1) = (Key::Hash([1u8; 32]), Value::Int32(1));
    let (k2, v2) = (Key::Hash([2u8; 32]), Value::Int32(2));
    let (k3, v3) = (Key::Hash([3u8; 32]), Value::Int32(3));
    tc_cache.insert_write(k1, v1.clone());
    tc_cache.insert_read(k2, v2.clone());
    tc_cache.insert_read(k3, v3.clone());
    // Writes are not subject to cache invalidation
    assert_eq!(tc_cache.get(&k1), Some(&v1));
    assert_eq!(tc_cache.get(&k2), Some(&v2)); // k2 and k3 should be there
    assert_eq!(tc_cache.get(&k3), Some(&v3));
}
