use std::collections::btree_map::BTreeMap;

use common::key::Key;
use common::value::{Account, Value};

pub fn mocked_account(account_addr: [u8; 32]) -> Vec<(Key, Value)> {
    let account = Account::new(account_addr, 0, BTreeMap::new());
    vec![(Key::Account(account_addr), Value::Account(account))]
}
