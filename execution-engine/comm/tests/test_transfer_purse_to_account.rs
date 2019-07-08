extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::key::Key;
use common::value::account::PublicKey;
use common::value::{Value, U512};
use shared::transform::Transform;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [12; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];

#[ignore]
#[test]
fn should_run_purse_to_account_transfer() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let transfer_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_account.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (account_1_public_key, U512::from(42)),
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    // Get transforms output for genesis account
    let account_transforms = transform
        .get(&Key::Account(GENESIS_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Inspect AddKeys for that account
    let modified_account = if let Transform::Write(Value::Account(account)) = account_transforms {
        account
    } else {
        panic!(
            "Transform {:?} is not a Transform with a Value(Account)",
            account_transforms
        );
    };

    // Obtain main purse's balance
    let final_balance = &transform[&modified_account.urefs_lookup()["final_balance"].normalize()];
    let final_balance = if let Transform::Write(Value::UInt512(balance)) = final_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            final_balance
        );
    };
    assert_eq!(final_balance, &U512::from(999_958));

    // Get the `transfer_result` for a given account
    let transfer_result_transform =
        &transform[&modified_account.urefs_lookup()["transfer_result"].normalize()];
    let transfer_result_string =
        if let Transform::Write(Value::String(s)) = transfer_result_transform {
            s
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(transfer_result_string, "TransferredToNewAccount");

    // Get transforms output for new account
    let new_account_transforms = transform
        .get(&Key::Account(ACCOUNT_1_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Inspect AddKeys for that new account to find it's purse id
    let new_account = if let Transform::Write(Value::Account(account)) = new_account_transforms {
        account
    } else {
        panic!(
            "Transform {:?} is not a Transform with a Value(Account)",
            account_transforms
        );
    };

    let new_purse_id = new_account.purse_id();
    // This is the new PurseId lookup key that will be present in AddKeys for a mint contract uref
    let new_purse_id_lookup_key = format!("{:?}", new_purse_id.value().addr());

    // Obtain transforms for a mint account
    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();
    let mint_transforms = transform
        .get(&mint_contract_uref.into())
        .expect("Unable to find transforms for a mint");

    // Inspect AddKeyse entry for that new account inside mint transforms
    let mint_addkeys = if let Transform::AddKeys(value) = mint_transforms {
        value
    } else {
        panic!("Transform {:?} is not AddKeys", mint_transforms);
    };

    // Find new account's purse uref
    let new_account_purse_uref = &mint_addkeys[&new_purse_id_lookup_key];

    let new_purse_transform = &transform[&new_account_purse_uref.normalize()];
    let purse_secondary_balance =
        if let Transform::Write(Value::UInt512(value)) = new_purse_transform {
            value
        } else {
            panic!("actual purse uref should be a Write of UInt512 type");
        };
    assert_eq!(purse_secondary_balance, &U512::from(42));
}
