use super::op::Op;
use super::transform::Transform;
use crate::common::key::Key;
use crate::common::value::{Account, Value};
use std::collections::{BTreeMap, HashMap};

pub mod lmdb;

pub mod in_memory;

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

/// A reader of state
pub trait StateReader<K, V> {
    /// An error which occurs when reading state
    type Error;

    /// Returns the state value from the corresponding key
    fn read(&self, key: &K) -> Result<Option<V>, Self::Error>;
}

pub fn mocked_account(account_addr: [u8; 20]) -> Vec<(Key, Value)> {
    let account = Account::new([48u8; 32], 0, BTreeMap::new());
    vec![(Key::Account(account_addr), Value::Account(account))]
}
