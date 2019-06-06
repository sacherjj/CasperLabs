use std::collections::btree_map::BTreeMap;

use common::key::Key;
use common::uref::{AccessRights, URef};
use common::value::account::{AccountActivity, AssociatedKeys, BlockTime, PublicKey, Weight};
use common::value::{Account, Value};

pub fn mocked_account(account_addr: [u8; 32]) -> Vec<(Key, Value)> {
    let associated_keys = {
        let mut associated_keys = AssociatedKeys::empty();
        associated_keys
            .add_key(PublicKey::new(account_addr), Weight::new(1))
            .unwrap();
        associated_keys
    };
    let account = Account::new(
        account_addr,
        0,
        BTreeMap::new(),
        URef::new([0u8; 32], AccessRights::READ_ADD_WRITE),
        associated_keys,
        Default::default(),
        AccountActivity::new(BlockTime(0), BlockTime(100)),
    );
    vec![(Key::Account(account_addr), Value::Account(account))]
}
