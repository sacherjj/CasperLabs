use std::collections::btree_map::BTreeMap;

use common::key::Key;
use common::uref::{AccessRights, URef};
use common::value::account::PurseId;
use common::value::{Account, Value};

pub fn mocked_account(account_addr: [u8; 32]) -> Vec<(Key, Value)> {
    let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
    let account = Account::create(account_addr, BTreeMap::new(), purse_id);
    vec![(Key::Account(account_addr), Value::Account(account))]
}
