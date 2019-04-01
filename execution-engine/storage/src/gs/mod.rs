use super::op::Op;
use super::transform::Transform;
use crate::common::key::Key;
use crate::common::value::{Account, Value};
use std::collections::{BTreeMap, HashMap};

pub mod inmem;
pub mod lmdb;

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

pub trait DbReader {
    type Error;
    fn get(&self, k: &Key) -> Result<Option<Value>, Self::Error>;
}

pub fn mocked_account(account_addr: [u8; 20]) -> BTreeMap<Key, Value> {
    let account = Account::new([48u8; 32], 0, BTreeMap::new());
    let mut map = BTreeMap::new();
    map.insert(Key::Account(account_addr), Value::Account(account));

    map
}
